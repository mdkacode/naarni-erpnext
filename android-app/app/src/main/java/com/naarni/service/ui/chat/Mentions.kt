package com.naarni.service.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle

/**
 * Everything `@` needs, as pure functions over strings.
 *
 * Kept out of the composables deliberately: the token-scanning rules below are
 * the fiddly part of the feature, and they are far easier to reason about — and
 * to test — with no Compose state anywhere near them.
 */
object Mentions {

    /**
     * How many spaces a mention token may contain before we stop treating it as
     * one. Names in this fleet are routinely "Ravi Kumar Singh", so a token that
     * ended at the first space would make the autocomplete useless for exactly
     * the people it is meant to find. Three words is where a sentence that
     * happens to contain an email address stops looking like a name.
     */
    private const val MAX_SPACES = 2

    /** A live `@…` token at the caret: where it sits, and what has been typed. */
    data class Token(val range: IntRange, val query: String)

    /**
     * The mention being typed at [cursor], if any.
     *
     * The `@` must start a word. Without that rule every email address in a
     * message would open the picker mid-sentence.
     */
    fun activeToken(text: String, cursor: Int): Token? {
        if (cursor <= 0 || cursor > text.length) return null
        var index = cursor - 1
        var spaces = 0
        while (index >= 0) {
            when (text[index]) {
                '@' -> {
                    val preceding = if (index == 0) ' ' else text[index - 1]
                    if (!preceding.isWhitespace()) return null
                    return Token(index until cursor, text.substring(index + 1, cursor))
                }

                '\n' -> return null
                ' ' -> {
                    spaces++
                    if (spaces > MAX_SPACES) return null
                }
            }
            index--
        }
        return null
    }

    /**
     * Swap the token under the caret for a chosen name, and leave the caret past
     * the trailing space so typing simply continues.
     */
    fun applyPick(value: TextFieldValue, token: Token, displayName: String): TextFieldValue {
        val replacement = "@$displayName "
        val text = value.text.replaceRange(token.range, replacement)
        val caret = token.range.first + replacement.length
        return TextFieldValue(
            text = text,
            selection = androidx.compose.ui.text.TextRange(caret),
        )
    }

    /**
     * Which of the people picked while composing are still named in [body].
     *
     * Resolved at send time rather than tracked as the text is edited: someone
     * who backspaces over a name has un-mentioned that person, and a mention
     * that fires for a name no longer on screen is the kind of thing that makes
     * people distrust the feature entirely.
     */
    fun survivingMentions(body: String, picked: Map<String, String>): List<String> =
        picked.entries
            .filter { body.contains("@${it.key}") }
            .map { it.value }
            .distinct()

    /**
     * [body] with every `@name` in [labels] tinted and set slightly heavier.
     *
     * Matching is longest-label-first so "@Ravi Kumar" wins over "@Ravi" when
     * both are in the room; the shorter one would otherwise claim the prefix and
     * leave the surname unstyled.
     */
    fun annotate(body: String, labels: Collection<String>, color: Color): AnnotatedString {
        if (labels.isEmpty() || '@' !in body) return AnnotatedString(body)
        val ordered = labels.filter { it.isNotBlank() }.sortedByDescending { it.length }

        return buildAnnotatedString {
            var i = 0
            while (i < body.length) {
                val at = body.indexOf('@', i)
                if (at < 0) {
                    append(body.substring(i))
                    return@buildAnnotatedString
                }
                append(body.substring(i, at))

                val hit = ordered.firstOrNull { body.startsWith("@$it", at) }
                if (hit == null) {
                    append('@')
                    i = at + 1
                    continue
                }
                withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) {
                    append("@$hit")
                }
                i = at + 1 + hit.length
            }
        }
    }
}
