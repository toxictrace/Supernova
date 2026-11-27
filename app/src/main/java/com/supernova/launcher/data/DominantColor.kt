package com.supernova.launcher.data

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.get
import androidx.core.graphics.scale

// Cheap dominant color: downsample to 8x8 and average the saturated pixels,
// falling back to every opaque pixel for grayscale icons. Dynamic badges and
// the Circle launch animation take their color from it.
object DominantColor {
    private const val SAMPLE = 8
    private const val OPAQUE_ALPHA = 128
    private const val MIN_SATURATION = 0.35f
    private const val MIN_VALUE = 0.15f
    private const val HSV = 3

    fun of(bitmap: Bitmap?): Int? {
        bitmap ?: return null
        val sample = bitmap.scale(SAMPLE, SAMPLE)
        val all = Sum()
        val saturated = Sum()
        val hsv = FloatArray(HSV)
        for (y in 0 until SAMPLE) {
            for (x in 0 until SAMPLE) {
                val pixel = sample[x, y]
                if (Color.alpha(pixel) < OPAQUE_ALPHA) continue
                all.add(pixel)
                Color.colorToHSV(pixel, hsv)
                if (hsv[1] >= MIN_SATURATION && hsv[2] >= MIN_VALUE) saturated.add(pixel)
            }
        }
        sample.recycle()
        return saturated.average() ?: all.average()
    }

    // CIE76 distance in CIELAB makes "nearest" follow perceived colour differences,
    // rather than the uneven distances of raw RGB values.
    fun closestIndex(target: Int?, candidates: List<Int?>): Int {
        if (target == null || candidates.isEmpty()) return 0
        val targetLab = lab(target)
        var bestIndex = 0
        var bestDistance = Double.POSITIVE_INFINITY
        candidates.forEachIndexed { index, candidate ->
            candidate ?: return@forEachIndexed
            val candidateLab = lab(candidate)
            val deltaL = targetLab[0] - candidateLab[0]
            val deltaA = targetLab[1] - candidateLab[1]
            val deltaB = targetLab[2] - candidateLab[2]
            val distance = deltaL * deltaL + deltaA * deltaA + deltaB * deltaB
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex
    }

    private fun lab(color: Int): DoubleArray {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        fun axis(value: Double): Double {
            val delta = 6.0 / 29.0
            return if (value > delta * delta * delta) Math.pow(value, 1.0 / 3.0)
            else value / (3.0 * delta * delta) + 4.0 / 29.0
        }

        val red = linear(color ushr 16 and 0xFF)
        val green = linear(color ushr 8 and 0xFF)
        val blue = linear(color and 0xFF)
        val x = axis((0.4124564 * red + 0.3575761 * green + 0.1804375 * blue) / 0.95047)
        val y = axis(0.2126729 * red + 0.7151522 * green + 0.0721750 * blue)
        val z = axis((0.0193339 * red + 0.1191920 * green + 0.9503041 * blue) / 1.08883)
        return doubleArrayOf(116.0 * y - 16.0, 500.0 * (x - y), 200.0 * (y - z))
    }

    private class Sum {
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0

        fun add(pixel: Int) {
            r += Color.red(pixel)
            g += Color.green(pixel)
            b += Color.blue(pixel)
            count++
        }

        fun average(): Int? =
            if (count == 0) null else Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }
}
