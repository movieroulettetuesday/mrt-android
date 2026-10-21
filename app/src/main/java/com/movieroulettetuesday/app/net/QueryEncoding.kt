package com.movieroulettetuesday.app.net

/**
 * Foundation `URLComponents.queryItems` value encoding, so links match iOS byte for byte: RFC 3986
 * query characters stay literal (including `+`, `?`, `/`, `:`), `&`, `=`, `#`, space and everything
 * else are percent-encoded as UTF-8.
 */
object QueryEncoding {
    private const val LITERAL = "!$'()*+,;:@/?-._~"

    fun value(text: String): String {
        val out = StringBuilder()
        for (b in text.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            if (v < 0x80 && (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in LITERAL)) out.append(c) else out.append("%%%02X".format(v))
        }
        return out.toString()
    }
}
