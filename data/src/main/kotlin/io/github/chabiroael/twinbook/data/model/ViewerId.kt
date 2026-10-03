package io.github.chabiroael.twinbook.data.model

/**
 * The viewer's own id in recorded sessions and fixtures, which replace it (docs/CAPTURE.md,
 * "Secrets that are JSON numbers"). Readers accept both forms: the quoted layer 2 placeholder
 * [PLACEHOLDER] (sessions finalized since M2c, and every string id), and `0` (a bare placeholder
 * of an older session after the offline tools repaired it, and the fixtures built from those,
 * which are not regenerated).
 */
object ViewerId {
    const val PLACEHOLDER = "!T:cookie:c_user!"

    /** [value] as parsed from JSON: a String, a Number, or anything else. */
    fun matches(value: Any?): Boolean = when (value) {
        is String -> value == PLACEHOLDER || value == "0"
        is Number -> value.toDouble() == 0.0
        else -> false
    }
}
