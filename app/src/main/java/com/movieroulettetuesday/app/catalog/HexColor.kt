package com.movieroulettetuesday.app.catalog

import kotlin.math.pow

/** Parsed "#RRGGBB" / "#RRGGBBAA" color (components 0..1). Port of RouletteCore/HexColor.swift. */
data class HexColor(val red: Double, val green: Double, val blue: Double, val alpha: Double = 1.0) {
    /** WCAG relative luminance. */
    val relativeLuminance: Double
        get() {
            fun linear(c: Double) = if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
        }

    /** True when black text has better WCAG contrast on this color than white. */
    val prefersDarkText: Boolean
        get() = (relativeLuminance + 0.05) / 0.05 > 1.05 / (relativeLuminance + 0.05)

    /** ARGB int for Compose `Color(Int)`. */
    val argb: Int
        get() {
            fun b(c: Double) = Math.round(c * 255).toInt() and 0xFF
            return (b(alpha) shl 24) or (b(red) shl 16) or (b(green) shl 8) or b(blue)
        }

    /** Hue (0..<1), saturation, brightness, exactly like iOS `HexColor.hsb`. */
    val hsb: Triple<Double, Double, Double>
        get() {
            val maxC = maxOf(red, green, blue)
            val minC = minOf(red, green, blue)
            val delta = maxC - minC
            var hue = 0.0
            if (delta > 0) {
                hue = when (maxC) {
                    red -> ((green - blue) / delta) % 6 // truncatingRemainder
                    green -> (blue - red) / delta + 2
                    else -> (red - green) / delta + 4
                }
                hue /= 6
                if (hue < 0) hue += 1
            }
            return Triple(hue, if (maxC == 0.0) 0.0 else delta / maxC, maxC)
        }

    /** "#RRGGBB" upper-case, alpha dropped (iOS `hexString`). */
    val hexString: String
        get() {
            fun byte(c: Double) = Math.round(c * 255).toInt()
            return "#%02X%02X%02X".format(byte(red), byte(green), byte(blue))
        }

    companion object {
        /** iOS `HexColor(hue:saturation:brightness:)`. */
        fun fromHsb(hue: Double, saturation: Double, brightness: Double, alpha: Double = 1.0): HexColor {
            val h = (if (hue.isFinite()) hue - kotlin.math.floor(hue) else 0.0) * 6
            val s = (if (saturation.isFinite()) saturation else 0.0).coerceIn(0.0, 1.0)
            val v = (if (brightness.isFinite()) brightness else 0.0).coerceIn(0.0, 1.0)
            val i = h.toInt() % 6
            val f = h - kotlin.math.floor(h)
            val p = v * (1 - s)
            val q = v * (1 - s * f)
            val t = v * (1 - s * (1 - f))
            val (r, g, b) = when (i) {
                0 -> Triple(v, t, p)
                1 -> Triple(q, v, p)
                2 -> Triple(p, v, t)
                3 -> Triple(p, q, v)
                4 -> Triple(t, p, v)
                else -> Triple(v, p, q)
            }
            return HexColor(r, g, b, alpha)
        }

        private val HEX = Regex("^[0-9A-Fa-f]+$")

        fun parse(hex: String): HexColor? {
            var s = hex.trim()
            if (s.startsWith("#")) s = s.substring(1)
            if ((s.length != 6 && s.length != 8) || !HEX.matches(s)) return null
            val v = s.toLong(16)
            return if (s.length == 6) {
                HexColor(((v shr 16) and 0xFF) / 255.0, ((v shr 8) and 0xFF) / 255.0, (v and 0xFF) / 255.0, 1.0)
            } else {
                HexColor(
                    ((v shr 24) and 0xFF) / 255.0, ((v shr 16) and 0xFF) / 255.0,
                    ((v shr 8) and 0xFF) / 255.0, (v and 0xFF) / 255.0,
                )
            }
        }
    }
}
