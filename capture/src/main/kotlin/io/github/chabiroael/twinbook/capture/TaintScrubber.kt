package io.github.chabiroael.twinbook.capture

/**
 * Layer 2 redaction (taint): replaces every occurrence of every remembered secret value in
 * stored capture data, including data recorded before the value was first recognized.
 *
 * Kotlin twin of extension/src/lib/taint.ts; both run the shared vectors in
 * extension/test/vectors/taint.json. The specification is docs/CAPTURE.md section 5:
 *
 * 1. A value is eligible if it has at least [TaintOptions.minLength] UTF-16 characters, is not
 *    in the stop list (case-insensitive), is not one repeated character, and is not a layer 1
 *    placeholder. Shorter values are only redacted where layer 1 finds them under their key.
 * 2. Each eligible value is searched as is, URL-encoded (as JavaScript's encodeURIComponent),
 *    form-encoded (space as +), JSON-escaped (as JSON.stringify), JSON-escaped with "/" as
 *    "\/", JSON-escaped twice, and HTML-escaped. Variants shorter than minLength in UTF-8 bytes
 *    are skipped. If two values share a variant, the first value's label wins.
 * 3. Data is scanned as bytes from left to right; at each position the longest matching variant
 *    is replaced by `!T:<label>!` and the scan continues after it.
 *
 * Secret values live only in memory: in this object while it exists, never on disk.
 */
class TaintScrubber(secrets: List<Secret>, private val options: TaintOptions = TaintOptions()) {
    private class Pattern(val bytes: ByteArray, val label: String, val placeholder: ByteArray)

    /** Patterns by their first byte, longest first. */
    private val byFirst = arrayOfNulls<Array<Pattern>>(256)

    /** Number of secret values that were eligible. */
    val eligibleValues: Int

    /** Number of distinct byte patterns searched for. */
    val patternCount: Int

    init {
        val seen = HashSet<String>()
        val lists = HashMap<Int, MutableList<Pattern>>()
        var eligible = 0
        for (s in secrets) {
            if (!isEligible(s.value, options)) continue
            eligible++
            val label = sanitizeLabel(s.label)
            val placeholder = l2Placeholder(label).toByteArray(Charsets.UTF_8)
            for (v in variants(s.value)) {
                val bytes = v.toByteArray(Charsets.UTF_8)
                val key = String(bytes, Charsets.ISO_8859_1)
                if (bytes.size < options.minLength || !seen.add(key)) continue
                lists.getOrPut(bytes[0].toInt() and 0xff) { mutableListOf() } += Pattern(bytes, label, placeholder)
            }
        }
        for ((first, list) in lists) byFirst[first] = list.sortedByDescending { it.bytes.size }.toTypedArray()
        eligibleValues = eligible
        patternCount = seen.size
    }

    /** Result of scrubbing one piece of data. */
    class Result(val bytes: ByteArray, val counts: Map<String, Int>) {
        val replacements: Int get() = counts.values.sum()
    }

    fun scrub(data: ByteArray): Result {
        val counts = LinkedHashMap<String, Int>()
        if (patternCount == 0) return Result(data, counts)
        var out: java.io.ByteArrayOutputStream? = null
        var last = 0
        var i = 0
        while (i < data.size) {
            val hit = match(data, i)
            if (hit == null) {
                i++
                continue
            }
            val o = out ?: java.io.ByteArrayOutputStream(data.size).also { out = it }
            o.write(data, last, i - last)
            o.write(hit.placeholder)
            counts[hit.label] = (counts[hit.label] ?: 0) + 1
            i += hit.bytes.size
            last = i
        }
        val o = out ?: return Result(data, counts)
        o.write(data, last, data.size - last)
        return Result(o.toByteArray(), counts)
    }

    /** Number of positions where a variant still occurs (0 after a complete scrub). */
    fun countHits(data: ByteArray): Int {
        var hits = 0
        for (i in data.indices) if (match(data, i) != null) hits++
        return hits
    }

    private fun match(data: ByteArray, at: Int): Pattern? {
        val candidates = byFirst[data[at].toInt() and 0xff] ?: return null
        for (p in candidates) if (regionMatches(data, at, p.bytes)) return p
        return null
    }

    private fun regionMatches(data: ByteArray, at: Int, pattern: ByteArray): Boolean {
        if (at + pattern.size > data.size) return false
        for (k in pattern.indices) if (data[at + k] != pattern[k]) return false
        return true
    }

    companion object {
        private val PLACEHOLDER = Regex("""^(?:!R\**!|\*{1,2})$""")

        fun isPlaceholder(value: String): Boolean = PLACEHOLDER.matches(value)

        fun isEligible(value: String, options: TaintOptions = TaintOptions()): Boolean {
            if (value.length < options.minLength) return false
            val lower = value.lowercase()
            if (options.stopList.any { it.lowercase() == lower }) return false
            if (value.codePoints().distinct().count() == 1L) return false
            return !isPlaceholder(value)
        }

        /** Label text allowed in a placeholder: as the extension's sanitizeLabel, per UTF-16 unit. */
        fun sanitizeLabel(label: String): String {
            val sb = StringBuilder(label.length)
            for (c in label) sb.append(if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '.' || c == ':' || c == '-') c else '_')
            return sb.toString().take(80)
        }

        fun l2Placeholder(label: String): String = "!T:${sanitizeLabel(label)}!"

        /** The encodings a value is searched in, distinct, in a fixed order. */
        fun variants(value: String): List<String> {
            val json = jsonEscape(value)
            val uri = encodeUriComponent(value)
            val list = mutableListOf(value)
            if (uri != null) {
                list += uri
                list += uri.replace("%20", "+")
            }
            list += json
            list += json.replace("/", "\\/")
            list += jsonEscape(json)
            list += htmlEscape(value)
            return list.distinct()
        }

        /** As JavaScript's JSON.stringify(value) without the quotes. */
        fun jsonEscape(value: String): String {
            val sb = StringBuilder(value.length + 8)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\b' -> sb.append("\\b")
                    c == '\u000C' -> sb.append("\\f")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c < ' ' -> sb.append("\\u%04x".format(c.code))
                    c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate() -> {
                        sb.append(c).append(value[i + 1])
                        i++
                    }
                    c.isSurrogate() -> sb.append("\\u%04x".format(c.code))
                    else -> sb.append(c)
                }
                i++
            }
            return sb.toString()
        }

        /** As JavaScript's encodeURIComponent; null for a value with a lone surrogate (JavaScript throws). */
        fun encodeUriComponent(value: String): String? {
            val sb = StringBuilder(value.length * 3)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-_.!~*'()") {
                    sb.append(c)
                    i++
                    continue
                }
                val cp: Int
                if (c.isHighSurrogate()) {
                    if (i + 1 >= value.length || !value[i + 1].isLowSurrogate()) return null
                    cp = Character.toCodePoint(c, value[i + 1])
                    i += 2
                } else {
                    if (c.isLowSurrogate()) return null
                    cp = c.code
                    i++
                }
                for (b in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) sb.append('%').append("%02X".format(b.toInt() and 0xff))
            }
            return sb.toString()
        }

        fun htmlEscape(value: String): String =
            value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    }
}

/** A secret value remembered by layer 1, with the label of its first sighting. */
class Secret(val value: String, val label: String) {
    override fun toString(): String = "Secret(label=$label, length=${value.length})"
}

/** Must match extension/data/redaction-rules.json (checked by a test). */
data class TaintOptions(
    val minLength: Int = 8,
    val stopList: List<String> = listOf("true", "false", "null", "undefined", "deleted"),
)
