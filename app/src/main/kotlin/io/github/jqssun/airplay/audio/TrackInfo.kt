package io.github.jqssun.airplay.audio

import android.graphics.Bitmap

data class TrackInfo(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val genre: String = "",
    val durationMs: Long = 0,
    val coverArt: Bitmap? = null,
) {
    companion object {
        fun fromDmap(map: Map<String, Any>, existingArt: Bitmap? = null): TrackInfo {
            // DMAP data is often wrapped in mlit container — flatten it
            val flat = _flatten(map)
            return TrackInfo(
                title = flat["minm"] as? String ?: "",
                artist = flat["asar"] as? String ?: "",
                album = flat["asal"] as? String ?: "",
                genre = flat["asgn"] as? String ?: "",
                durationMs = (flat["astm"] as? Long) ?: 0L,
                coverArt = existingArt,
            )
        }

        @Suppress("UNCHECKED_CAST")
        private fun _flatten(map: Map<String, Any>): Map<String, Any> {
            val result = mutableMapOf<String, Any>()
            for ((k, v) in map) {
                if (v is Map<*, *>) {
                    result.putAll(_flatten(v as Map<String, Any>))
                } else {
                    result[k] = v
                }
            }
            return result
        }
    }
}
