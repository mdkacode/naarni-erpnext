package com.naarni.service.ui.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The reaction vocabulary, and how a stored row becomes chips.
 *
 * The set is duplicated from the server's `REACTIONS` rather than fetched,
 * because it is drawn before any network call could return and a picker that
 * pops in a second late is a picker people stop trusting. The server remains
 * the authority: it refuses a code it does not know, so the two can only drift
 * in the direction of the app offering something that gets rejected — never in
 * the direction of accepting junk.
 */
object Reactions {

    /** Ordered, and the order is load-bearing: chips must not move about. */
    val ALL: List<Pair<String, String>> = listOf(
        "like" to "👍",
        "love" to "❤️",
        "haha" to "😂",
        "wow" to "😮",
        "sad" to "😢",
        "thanks" to "🙏",
    )

    private val glyphs = ALL.toMap()

    @Serializable
    data class Chip(
        val code: String,
        val emoji: String = "",
        val users: List<String> = emptyList(),
        val count: Int = 0,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse the stored JSON array. Never throws.
     *
     * A row written by a newer server could carry a code this build has no
     * glyph for; rather than dropping the chip — which would make a reaction
     * silently vanish for anyone on an older app — it falls back to the emoji
     * the server sent, and only skips the chip if that is empty too.
     */
    fun parse(raw: String?): List<Chip> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(Chip.serializer()), raw)
                .map { chip -> chip.copy(emoji = glyphs[chip.code] ?: chip.emoji) }
                .filter { it.emoji.isNotBlank() && it.count > 0 }
        }.getOrDefault(emptyList())
    }
}
