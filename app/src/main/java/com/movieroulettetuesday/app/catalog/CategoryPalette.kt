package com.movieroulettetuesday.app.catalog

import com.movieroulettetuesday.app.shared.SharedPalette

/**
 * The single source of category colors for every surface (iOS RouletteCore/CategoryPalette.swift).
 * Known ids use the muted "Clean Pro" colors from mrt-shared `data/palette.json`; an unknown id gets a
 * muted version of its catalog hex; no usable hex picks a stable fallback by FNV-1a of the id.
 */
object CategoryPalette {
    val known: Map<String, String> = SharedPalette.knownCategoryColors
    val fallback: List<String> = SharedPalette.fallbackOrder

    fun hex(categoryId: String, catalogHex: String? = null): String {
        known[categoryId]?.let { return it }
        catalogHex?.let(HexColor::parse)?.let { return muted(it).hexString }
        return fallback[StableHash.index(categoryId, fallback.size)]
    }

    fun muted(color: HexColor): HexColor {
        val (h, s, b) = color.hsb
        return HexColor.fromHsb(h, minOf(s * 0.7, 0.5), b.coerceIn(0.58, 0.8))
    }

    /** Center (lighter, softer) -> base -> slightly deeper edge, for a wheel slice. */
    fun radialStops(hex: String): List<String> {
        val base = HexColor.parse(hex) ?: return listOf(hex, hex, hex)
        val (h, s, b) = base.hsb
        val center = HexColor.fromHsb(h, s * 0.55, minOf(1.0, b * 1.2))
        val edge = HexColor.fromHsb(h, s, b * 0.9)
        return listOf(center.hexString, base.hexString, edge.hexString)
    }
}

/** FNV-1a 64 over UTF-8 (iOS `stableIndex` / SpinOfTheDay `stableHash`). */
object StableHash {
    fun fnv1a64(text: String): ULong {
        var hash = 0xcbf29ce484222325uL
        for (byte in text.encodeToByteArray()) {
            hash = hash xor byte.toUByte().toULong()
            hash *= 0x100000001b3uL
        }
        return hash
    }

    fun index(id: String, count: Int): Int = if (count <= 0) 0 else (fnv1a64(id) % count.toULong()).toInt()
}

/** The color to draw this category with everywhere in the UI. */
val RouletteCategory.displayHex: String get() = CategoryPalette.hex(id, colorHex)

/** Parsed [displayHex] (always valid for known/fallback ids). */
val RouletteCategory.displayColor: HexColor get() = HexColor.parse(displayHex) ?: HexColor(0.5, 0.5, 0.5)
