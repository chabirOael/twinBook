package io.github.chabiroael.twinbook.capture

/** Minimal JSON writer for session.json: maps with string keys, lists, strings, numbers, booleans, null. */
object JsonWriter {
    fun write(value: Any?): String = StringBuilder().also { append(it, value, 0) }.toString()

    private fun append(sb: StringBuilder, value: Any?, indent: Int) {
        when (value) {
            null -> sb.append("null")
            is String -> sb.append('"').append(TaintScrubber.jsonEscape(value)).append('"')
            is Boolean, is Int, is Long -> sb.append(value)
            is Double -> sb.append(if (value.isFinite()) value.toString() else "null")
            is Number -> sb.append(value.toString())
            is Map<*, *> -> block(sb, '{', '}', value.entries.toList(), indent) { e ->
                sb.append('"').append(TaintScrubber.jsonEscape(e.key.toString())).append("\": ")
                append(sb, e.value, indent + 1)
            }
            is Collection<*> -> block(sb, '[', ']', value.toList(), indent) { append(sb, it, indent + 1) }
            is Array<*> -> append(sb, value.toList(), indent)
            else -> sb.append('"').append(TaintScrubber.jsonEscape(value.toString())).append('"')
        }
    }

    private fun <T> block(sb: StringBuilder, open: Char, close: Char, items: List<T>, indent: Int, each: (T) -> Unit) {
        if (items.isEmpty()) {
            sb.append(open).append(close)
            return
        }
        sb.append(open).append('\n')
        items.forEachIndexed { i, item ->
            repeat(indent + 1) { sb.append("  ") }
            each(item)
            if (i < items.size - 1) sb.append(',')
            sb.append('\n')
        }
        repeat(indent) { sb.append("  ") }
        sb.append(close)
    }
}
