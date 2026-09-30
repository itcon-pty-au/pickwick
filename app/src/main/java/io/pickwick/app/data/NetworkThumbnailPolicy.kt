package io.pickwick.app.data

internal object NetworkThumbnailPolicy {
    const val SUFFIX = "-scene-v2.jpg"

    fun timesUs(durationSeconds: Long): List<Long> =
        if (durationSeconds > 0) listOf(0.25, 0.5, 0.75).map {
            (durationSeconds * 1_000_000.0 * it).toLong()
        } else listOf(30_000_000L, 10_000_000L, 0L)

    fun isNearBlack(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return true
        val lit = pixels.count {
            val r = (it shr 16) and 255
            val g = (it shr 8) and 255
            val b = it and 255
            (r * 299 + g * 587 + b * 114) / 1000 > 24
        }
        return lit.toDouble() / pixels.size < 0.05
    }
}
