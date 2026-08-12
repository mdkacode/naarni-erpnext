package com.naarni.service.ui.chat

import com.naarni.service.data.chat.ChatMessageEntity

/**
 * Sorting a conversation's attachments into the tabs a gallery shows.
 *
 * Pure functions, kept out of the composable so the bucketing rules can be
 * tested without a device — the categories are the part users notice being
 * wrong (a PDF filed under Media, a link that never appears), and that is
 * exactly the sort of thing a UI test would not catch cheaply.
 */
object Gallery {

    enum class Tab(val label: String) {
        MEDIA("Media"),
        DOCUMENTS("Documents"),
        VOICE("Voice"),
        LINKS("Links"),
    }

    /**
     * URLs inside a message body.
     *
     * Bare `www.` hosts count — people paste them constantly and a links tab
     * that silently drops half of them is worse than none. The trailing-
     * punctuation trim matters because a URL at the end of a sentence otherwise
     * carries the full stop into the link and 404s.
     */
    private val URL = Regex("""(https?://|www\.)[^\s<>"']+""", RegexOption.IGNORE_CASE)

    fun linksIn(body: String): List<String> =
        URL.findAll(body)
            .map { it.value.trimEnd('.', ',', ')', ']', '}', ';', ':', '!', '?') }
            .filter { it.length > 7 }
            .distinct()
            .toList()

    fun tabOf(message: ChatMessageEntity): Tab? = when (message.kind) {
        "image", "video" -> Tab.MEDIA
        "file" -> Tab.DOCUMENTS
        "audio" -> Tab.VOICE
        "text" -> if (linksIn(message.body).isNotEmpty()) Tab.LINKS else null
        else -> null
    }

    /**
     * Every tab, in display order, with its rows.
     *
     * Always returns all four keys — a tab strip whose tabs appear and vanish as
     * messages arrive is disorienting, and an empty tab with an honest empty
     * state tells the user more than a missing one does.
     */
    fun bucket(messages: List<ChatMessageEntity>): Map<Tab, List<ChatMessageEntity>> {
        val out = Tab.entries.associateWith { mutableListOf<ChatMessageEntity>() }
        for (message in messages) {
            tabOf(message)?.let { out.getValue(it).add(message) }
        }
        return out
    }

    /**
     * The kind of document, in the two or three letters that fit on a tile.
     *
     * Taken from the file name rather than a stored content type, because the
     * name is what the sender saw and what the recipient will recognise.
     */
    fun docKindOf(fileName: String?): String {
        val ext = (fileName ?: "").substringAfterLast('.', "").uppercase()
        return when {
            ext.isEmpty() || ext.length > 4 -> "FILE"
            else -> ext
        }
    }

    /** Human label for the counts under each tab. */
    fun countLabel(tab: Tab, n: Int): String = when {
        n == 0 -> "No ${tab.label.lowercase()} yet"
        n == 1 -> when (tab) {
            Tab.MEDIA -> "1 photo or video"
            Tab.DOCUMENTS -> "1 document"
            Tab.VOICE -> "1 voice note"
            Tab.LINKS -> "1 link"
        }
        else -> "$n ${tab.label.lowercase()}"
    }
}
