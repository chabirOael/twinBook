package io.github.chabiroael.twinbook.capture

/** Small JSON reader for the tests (objects become LinkedHashMap, arrays List, numbers Double or Long). */
class MiniJson private constructor(private val s: String) {
    private var i = 0

    companion object {
        fun parse(text: String): Any? = MiniJson(text).run {
            val v = value()
            ws()
            check(i == s.length) { "trailing data at $i" }
            v
        }
    }

    private fun ws() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun value(): Any? {
        ws()
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> lit("true", true)
            'f' -> lit("false", false)
            'n' -> lit("null", null)
            else -> if (c == '-' || c.isDigit()) num() else error("unexpected $c at $i")
        }
    }

    private fun lit(word: String, v: Any?): Any? {
        check(s.startsWith(word, i))
        i += word.length
        return v
    }

    private fun num(): Any {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        val t = s.substring(start, i)
        return t.toLongOrNull() ?: t.toDouble()
    }

    private fun obj(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        i++
        ws()
        if (s[i] == '}') return m.also { i++ }
        while (true) {
            ws()
            val k = str()
            ws()
            check(s[i++] == ':')
            m[k] = value()
            ws()
            if (s[i++] == '}') return m
        }
    }

    private fun arr(): List<Any?> {
        val l = mutableListOf<Any?>()
        i++
        ws()
        if (s[i] == ']') return l.also { i++ }
        while (true) {
            l += value()
            ws()
            if (s[i++] == ']') return l
        }
    }

    private fun str(): String {
        check(s[i++] == '"')
        val sb = StringBuilder()
        while (true) {
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> when (val e = s[i++]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'u' -> {
                        sb.append(s.substring(i, i + 4).toInt(16).toChar())
                        i += 4
                    }
                    else -> sb.append(e)
                }
                else -> sb.append(c)
            }
        }
    }
}
