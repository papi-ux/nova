package com.papi.nova.nvstream

/**
 * The stream a watcher is joining, as the host describes it.
 *
 * A watcher is handed the owner's encoded stream as it is, so its size, rate and bit depth are
 * the owner's whatever the watcher asks for. Polaris refuses a watch request that asks for anything
 * else, with 412 and "Watch mode must match the active stream profile (1280x800@90 HEVC 8-bit
 * 8000kbps)", and two devices almost never ask for the same thing: a Retroid at 1920x1080@60
 * could not watch a Deck at 1280x800@90. The refusal names the mode, so Nova reads it, takes it
 * and asks again. It is read from the sentence because that is all a released host sends.
 */
data class NovaWatchProfile(
    val width: Int,
    val height: Int,
    /** Frames per second times a thousand, the way the host keeps it: 60000, or 59940. */
    val fpsX1000: Int,
    val tenBit: Boolean,
) {
    val fps: Float get() = fpsX1000 / 1000f

    companion object {
        private val PATTERN =
            Regex("""\((\d{2,5})x(\d{2,5})@(\d{1,3})(?:\.(\d{1,3}))? \S+ (8|10)-bit \d+kbps\)""")

        @JvmStatic
        fun parse(message: String?): NovaWatchProfile? {
            val match = PATTERN.find(message ?: return null) ?: return null
            val (width, height, whole, fraction, bits) = match.destructured
            val millis = fraction.padEnd(3, '0').toIntOrNull() ?: 0
            val fpsX1000 = (whole.toIntOrNull() ?: return null) * 1000 + millis
            val parsedWidth = width.toIntOrNull() ?: return null
            val parsedHeight = height.toIntOrNull() ?: return null
            if (parsedWidth < 64 || parsedHeight < 64 || fpsX1000 < 1000) {
                return null
            }
            return NovaWatchProfile(parsedWidth, parsedHeight, fpsX1000, tenBit = bits == "10")
        }
    }
}
