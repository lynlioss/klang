package app.lynli.klang.data

import android.graphics.Bitmap
import com.materialkolor.hct.Hct
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score

/** Shared extraction for embedded artwork and custom playlist covers. */
internal object ArtworkColors {
    // App choices: enough clusters to retain small accents, with bounded work per cover.
    private const val SAMPLE_EDGE = 64
    private const val MAX_COLORS = 128
    private const val MIN_CHROMA = 12.0
    private const val MIN_COLOR_PROPORTION = 0.01

    fun hasColor(argb: Int): Boolean = Hct.fromInt(argb).chroma >= MIN_CHROMA

    fun seed(bitmap: Bitmap): Int? {
        val scale = minOf(1f, SAMPLE_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val sample = if (scale < 1f) Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        return try {
            val pixels = IntArray(sample.width * sample.height)
            sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
            seed(pixels)
        } finally {
            if (sample !== bitmap) sample.recycle()
        }
    }

    fun seed(pixels: IntArray): Int? {
        val opaque = pixels.filter { (it ushr 24) >= 128 }
            .map { it or 0xFF000000.toInt() }.toIntArray()
        if (opaque.isEmpty()) return null

        // Quantization can merge an accent into a flat background even with spare clusters.
        // Keep exact populations when the image already fits within the color budget.
        val populations = opaque.toList().groupingBy { it }.eachCount()
        val colors = if (populations.size <= MAX_COLORS) populations
            else QuantizerCelebi.quantize(opaque, MAX_COLORS)
        val dominant = colors.maxByOrNull { it.value }?.key ?: return null
        // Near-gray pixels must not outvote an accent or turn compression tint into a hue.
        val chromatic = colors.filterKeys(::hasColor)
        if (chromatic.values.sum() >= opaque.size * MIN_COLOR_PROPORTION) {
            Score.score(chromatic, desired = 1, fallbackColorArgb = null).firstOrNull()?.let { return it }
        }
        // A monochrome cover gets a neutral seed, including when it has isolated color noise.
        return Hct.fromInt(dominant).withChroma(0.0).toInt()
    }
}
