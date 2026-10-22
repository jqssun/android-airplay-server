package io.github.jqssun.airplay.renderer

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

// decodes into an app-owned SurfaceTexture that outlives display surface
// gl thread blits it to any surface attached, so fullscreen toggles re-point display without restarting codec
class VideoPipeline {

    private val lock = Object()
    private var thread: Thread? = null
    @Volatile private var running = false

    private var egl: EglCore? = null
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var winW = 0
    private var winH = 0

    private var oesTex = 0
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uTexMatrix = 0
    private var uCrop = 0
    private val texMatrix = FloatArray(16)
    private var hasFrame = false
    private var frameThread: HandlerThread? = null
    @Volatile var renderedFrames = 0L; private set

    private var surfaceTexture: SurfaceTexture? = null
    var inputSurface: Surface? = null; private set

    @Volatile private var frameAvailable = false
    private data class DisplayTarget(val surface: Surface?, val width: Int = 0, val height: Int = 0)
    private data class VideoSize(val width: Int = 0, val height: Int = 0)
    private var pendingDisplay = DisplayTarget(null)
    private var currentDisplay = DisplayTarget(null)
    private var displayDirty = false
    private var redraw = false
    @Volatile private var videoSize = VideoSize()
    @Volatile private var fillLandscape = false
    private var lastRenderGeometry = ""

    fun start() = synchronized(lock) {
        if (running) return@synchronized
        running = true
        thread = Thread({ _loop() }, "VideoPipeline").also { it.start() }
        while (inputSurface == null && running) lock.wait()
    }

    fun setDisplaySurface(surface: Surface?, width: Int = 0, height: Int = 0) = synchronized(lock) {
        pendingDisplay = DisplayTarget(surface, width, height)
        displayDirty = true
        lock.notifyAll()
    }

    fun setVideoSize(w: Int, h: Int) = synchronized(lock) {
        videoSize = VideoSize(w, h)
        surfaceTexture?.setDefaultBufferSize(w, h)
    }

    fun setFillLandscape(fill: Boolean) = synchronized(lock) {
        if (fillLandscape == fill) return@synchronized
        fillLandscape = fill
        redraw = true
        lock.notifyAll()
    }

    fun release() {
        synchronized(lock) {
            if (!running) return
            running = false
            lock.notifyAll()
        }
        thread?.join()
        thread = null
    }

    private fun _loop() {
        try {
            egl = EglCore()
            _initGl()
        } catch (e: Exception) {
            Log.e(TAG, "GL init failed", e)
            synchronized(lock) { running = false; lock.notifyAll() }
            return
        }
        Matrix.setIdentityM(texMatrix, 0)
        synchronized(lock) {
            inputSurface = Surface(surfaceTexture)
            lock.notifyAll()
        }
        while (true) {
            var newDisplay = DisplayTarget(null)
            var displayChanged = false
            var doFrame = false
            var doRedraw = false
            synchronized(lock) {
                while (running && !frameAvailable && !displayDirty && !redraw) lock.wait()
                if (running && displayDirty) {
                    newDisplay = pendingDisplay
                    displayChanged = true
                    displayDirty = false
                }
                if (running && frameAvailable) {
                    frameAvailable = false
                    doFrame = true
                }
                doRedraw = redraw
                redraw = false
            }
            if (!running) break
            if (displayChanged) _bindDisplay(newDisplay)
            if (doFrame) _consumeAndDraw()
            else if (doRedraw && hasFrame && window != EGL14.EGL_NO_SURFACE) _render()
        }
        _releaseGl()
    }

    private fun _bindDisplay(target: DisplayTarget) {
        val egl = egl ?: return
        val surface = target.surface
        // A size callback can arrive for the same Surface object. Keep its native
        // buffer dimensions rather than trusting an EGL query that can lag a resize.
        currentDisplay = if (surface === currentDisplay.surface && target.width <= 0) {
            currentDisplay
        } else target
        if (window != EGL14.EGL_NO_SURFACE) {
            egl.makeCurrent()
            egl.destroySurface(window)
            window = EGL14.EGL_NO_SURFACE
        }
        if (surface == null || !surface.isValid) return
        window = egl.windowSurface(surface)
        if (window == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "eglCreateWindowSurface failed: ${EGL14.eglGetError()}")
            return
        }
        egl.makeCurrent(window)
        winW = egl.query(window, EGL14.EGL_WIDTH)
        winH = egl.query(window, EGL14.EGL_HEIGHT)
        // an idle source sends no new frames, so repaint last one or new surface stays black
        if (hasFrame) _render()
    }

    private fun _consumeAndDraw() {
        val st = surfaceTexture ?: return
        val egl = egl ?: return
        if (window == EGL14.EGL_NO_SURFACE) {
            // no display: keep consuming so decoder doesn't stall
            egl.makeCurrent()
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            hasFrame = true
            return
        }
        egl.makeCurrent(window)
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        hasFrame = true
        _render()
    }

    private fun _render() {
        // setFixedSize/layout can resize the same Surface without replacing its object.
        // Never keep drawing with the old viewport after a portrait/landscape switch.
        val egl = egl ?: return
        val eglWidth = egl.query(window, EGL14.EGL_WIDTH)
        val eglHeight = egl.query(window, EGL14.EGL_HEIGHT)
        winW = currentDisplay.width.takeIf { it > 0 } ?: eglWidth
        winH = currentDisplay.height.takeIf { it > 0 } ?: eglHeight
        if (winW <= 0 || winH <= 0) return
        GLES20.glViewport(0, 0, winW, winH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val source = videoSize
        val framing = videoFraming(source.width, source.height, winW, winH, fillLandscape)
        val fit = framing.viewport
        GLES20.glViewport(fit.x, fit.y, fit.width, fit.height)
        val geometry = "source=${source.width}x${source.height} target=${winW}x$winH egl=${eglWidth}x$eglHeight " +
            "viewport=$fit crop=${framing.crop}"
        if (geometry != lastRenderGeometry) {
            Log.i(TAG, geometry)
            lastRenderGeometry = geometry
        }
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        framing.crop.let { GLES20.glUniform4f(uCrop, it.left, it.bottom, it.right, it.top) }
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, POS)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, TEX)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
        if (egl.swap(window)) renderedFrames++
    }

    private fun _initGl() {
        program = _buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uCrop = GLES20.glGetUniformLocation(program, "uCrop")
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTex = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val callbackThread = HandlerThread("AirPlayVideoFrames").apply { start() }
        frameThread = callbackThread
        surfaceTexture = SurfaceTexture(oesTex).also {
            val source = videoSize
            if (source.width > 0 && source.height > 0) it.setDefaultBufferSize(source.width, source.height)
            it.setOnFrameAvailableListener({
                synchronized(lock) { frameAvailable = true; lock.notifyAll() }
            }, Handler(callbackThread.looper))
        }
    }

    private fun _buildProgram(): Int {
        val vs = _shader(GLES20.GL_VERTEX_SHADER, VERT)
        val fs = _shader(GLES20.GL_FRAGMENT_SHADER, FRAG)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
        }
    }

    private fun _shader(type: Int, src: String): Int {
        return GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e(TAG, "shader compile failed: ${GLES20.glGetShaderInfoLog(it)}")
        }
    }

    private fun _releaseGl() {
        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture?.release()
        surfaceTexture = null
        inputSurface?.release()
        inputSurface = null
        frameThread?.quitSafely()
        frameThread = null
        egl?.let {
            if (window != EGL14.EGL_NO_SURFACE) it.destroySurface(window)
            it.close()
        }
        egl = null
        window = EGL14.EGL_NO_SURFACE
    }

    companion object {
        private const val TAG = "VideoPipeline"

        private val POS = _fb(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        private val TEX = _fb(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

        private fun _fb(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(a); position(0) }

        private const val VERT =
            "attribute vec2 aPos;\n" +
            "attribute vec2 aTex;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "uniform vec4 uCrop;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "  vTex = (uTexMatrix * vec4(mix(uCrop.xy, uCrop.zw, aTex), 0.0, 1.0)).xy;\n" +
            "}\n"

        private const val FRAG =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTex;\n" +
            "uniform samplerExternalOES sTex;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTex, vTex);\n" +
            "}\n"
    }
}
