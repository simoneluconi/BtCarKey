package com.simoneluconi.btcarkey.auth

/** Box serials ("A1:B2:C3:D4:E5:F6"), as advertised and as reported by AUTH. */
object Serials {
    /** Upper case with colons, from any common spelling; null if it isn't 6 bytes of hex. */
    fun normalize(raw: String): String? {
        val hex = raw.filter { it.isLetterOrDigit() }.uppercase()
        if (hex.length != 12 || hex.any { it !in "0123456789ABCDEF" }) return null
        return hex.chunked(2).joinToString(":")
    }

    /**
     * Same box. A byte-reversed serial counts too: advertising and AUTH might not use the same byte
     * order, and the box must still be recognised either way.
     */
    fun same(a: String, b: String): Boolean {
        val x = normalize(a) ?: return false
        val y = normalize(b) ?: return false
        return x == y || x == y.split(":").reversed().joinToString(":")
    }
}
