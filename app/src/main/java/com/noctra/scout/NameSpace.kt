package com.noctra.scout

/**
 * The space of 4-character usernames for a given character set.
 *
 * Names are visited in a shuffled-but-deterministic order so the scraper does not crawl
 * "aaaa, aaab, aaac..." in a block, yet the whole run resumes from a single integer cursor.
 * The shuffle is the bijection `index = (cursor * stride) mod total` with `gcd(stride, total) == 1`.
 */
class NameSpace(val charset: String, val length: Int = 4) {

    val total: Long = run {
        var t = 1L
        repeat(length) { t *= charset.length }
        t
    }

    private val stride: Long = coprimeStride(total)

    /** The name visited at [cursor] (0 until [total]). */
    fun nameAt(cursor: Long): String {
        var index = (cursor % total) * stride % total
        val out = CharArray(length)
        val base = charset.length
        for (i in length - 1 downTo 0) {
            out[i] = charset[(index % base).toInt()]
            index /= base
        }
        return String(out)
    }

    private fun coprimeStride(n: Long): Long {
        if (n <= 2L) return 1L
        var s = (n * 6180339887L / 10000000000L).coerceAtLeast(1L)
        var guard = 0
        while (gcd(s, n) != 1L && guard < 1000) {
            s++
            if (s >= n) s = 1L
            guard++
        }
        return s
    }

    private fun gcd(a: Long, b: Long): Long {
        var x = a
        var y = b
        while (y != 0L) {
            val t = x % y
            x = y
            y = t
        }
        return if (x < 0) -x else x
    }

    companion object {
        const val CHARSET_LETTERS = 0
        const val CHARSET_ALNUM = 1
        const val CHARSET_ALNUM_UNDERSCORE = 2

        private const val LETTERS = "abcdefghijklmnopqrstuvwxyz"
        private const val DIGITS = "0123456789"

        fun of(id: Int): NameSpace = when (id) {
            CHARSET_LETTERS -> NameSpace(LETTERS)
            CHARSET_ALNUM_UNDERSCORE -> NameSpace(LETTERS + DIGITS + "_")
            else -> NameSpace(LETTERS + DIGITS)
        }

        fun label(id: Int): String = when (id) {
            CHARSET_LETTERS -> "a–z"
            CHARSET_ALNUM_UNDERSCORE -> "a–z 0–9 _"
            else -> "a–z 0–9"
        }
    }
}
