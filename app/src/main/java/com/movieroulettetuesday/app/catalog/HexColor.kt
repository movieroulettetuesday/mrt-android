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

    companion object {
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
