package io.github.jqssun.airplay.renderer

/** Narrow opt-in for the H10 G4 firmware examined on the actual receiver. */
object H10HevcPolicy {
    const val DECODER = "OMX.hisi.video.decoder.hevc"

    @JvmStatic
    fun accepts(model: String, hardware: String, sdk: Int, decoder: String): Boolean {
        val normalizedModel = model.filter { it.isLetterOrDigit() }
        return normalizedModel.equals("H10G4", ignoreCase = true) &&
            hardware.equals("bigfish", ignoreCase = true) && sdk == 24 &&
            decoder.equals(DECODER, ignoreCase = true)
    }
}
