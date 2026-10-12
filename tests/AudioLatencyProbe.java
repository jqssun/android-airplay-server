public final class AudioLatencyProbe {
    public static void main(String[] args) throws Exception {
        Class<?> system = Class.forName("android.media.AudioSystem");
        java.lang.reflect.Method method = system.getDeclaredMethod("getOutputLatency", int.class);
        method.setAccessible(true);
        System.out.println("AudioSystem STREAM_MUSIC outputLatencyMs=" + method.invoke(null, 3));
    }
}
