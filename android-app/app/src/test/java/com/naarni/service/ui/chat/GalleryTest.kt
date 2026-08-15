package com.naarni.service.ui.chat

import com.naarni.service.data.chat.ChatMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gallery's categorisation rules.
 *
 * Worth testing without a device because a miscategorised attachment is
 * invisible rather than broken — a PDF filed under Media just quietly never
 * appears in Documents, and nobody reports it until they need the file.
 */
class GalleryTest {

    private fun msg(
        id: String = "c1",
        kind: String = "text",
        body: String = "",
        fileName: String? = null,
    ) = ChatMessageEntity(
        clientId = id,
        room = "room-1",
        sortSeq = 1,
        author = "a@b.com",
        authorName = "A",
        kind = kind,
        body = body,
        fileName = fileName,
    )

    // ------------------------------------------------------------------ links

    @Test
    fun `finds a plain https url`() {
        assertEquals(
            listOf("https://naarni.com/x"),
            Gallery.linksIn("see https://naarni.com/x please"),
        )
    }

    @Test
    fun `finds a bare www host`() {
        assertEquals(listOf("www.naarni.com"), Gallery.linksIn("go to www.naarni.com"))
    }

    @Test
    fun `drops the full stop that ends the sentence`() {
        assertEquals(
            listOf("https://naarni.com/report"),
            Gallery.linksIn("Filed at https://naarni.com/report."),
        )
    }

    @Test
    fun `drops a closing bracket around a url`() {
        assertEquals(
            listOf("https://naarni.com/a"),
            Gallery.linksIn("(see https://naarni.com/a)"),
        )
    }

    @Test
    fun `finds several distinct urls and dedupes repeats`() {
        val found = Gallery.linksIn("https://a.com/1 and https://b.com/2 and https://a.com/1")
        assertEquals(listOf("https://a.com/1", "https://b.com/2"), found)
    }

    @Test
    fun `plain text has no links`() {
        assertTrue(Gallery.linksIn("battery swapped, all good").isEmpty())
    }

    @Test
    fun `the word http alone is not a link`() {
        // The SQL pre-filter matches this row; the regex is what must reject it.
        assertTrue(Gallery.linksIn("we discussed http vs https today").isEmpty())
    }

    // ----------------------------------------------------------------- buckets

    @Test
    fun `images and videos share the media tab`() {
        assertEquals(Gallery.Tab.MEDIA, Gallery.tabOf(msg(kind = "image")))
        assertEquals(Gallery.Tab.MEDIA, Gallery.tabOf(msg(kind = "video")))
    }

    @Test
    fun `files are documents and audio is voice`() {
        assertEquals(Gallery.Tab.DOCUMENTS, Gallery.tabOf(msg(kind = "file")))
        assertEquals(Gallery.Tab.VOICE, Gallery.tabOf(msg(kind = "audio")))
    }

    @Test
    fun `text lands in links only when it carries one`() {
        assertEquals(Gallery.Tab.LINKS, Gallery.tabOf(msg(body = "https://naarni.com/a")))
        assertNull(Gallery.tabOf(msg(body = "no link here")))
    }

    @Test
    fun `system alert and ticket cards are not gallery content`() {
        assertNull(Gallery.tabOf(msg(kind = "system", body = "Ravi joined")))
        assertNull(Gallery.tabOf(msg(kind = "alert", body = "Pack 2 at 61C")))
        assertNull(Gallery.tabOf(msg(kind = "ticket", body = "TKT-001")))
    }

    @Test
    fun `bucket always returns every tab even when empty`() {
        val out = Gallery.bucket(emptyList())
        assertEquals(Gallery.Tab.entries.toSet(), out.keys)
        assertTrue(out.values.all { it.isEmpty() })
    }

    @Test
    fun `bucket splits a mixed conversation and preserves order`() {
        val out = Gallery.bucket(
            listOf(
                msg("1", kind = "image"),
                msg("2", kind = "file", fileName = "report.pdf"),
                msg("3", body = "https://naarni.com/a"),
                msg("4", kind = "image"),
                msg("5", kind = "system", body = "joined"),
                msg("6", kind = "audio"),
            )
        )
        assertEquals(listOf("1", "4"), out.getValue(Gallery.Tab.MEDIA).map { it.clientId })
        assertEquals(listOf("2"), out.getValue(Gallery.Tab.DOCUMENTS).map { it.clientId })
        assertEquals(listOf("6"), out.getValue(Gallery.Tab.VOICE).map { it.clientId })
        assertEquals(listOf("3"), out.getValue(Gallery.Tab.LINKS).map { it.clientId })
    }

    // --------------------------------------------------------------- doc badge

    @Test
    fun `doc badge is the extension, uppercased`() {
        assertEquals("PDF", Gallery.docKindOf("monthly report.pdf"))
        assertEquals("XLSX", Gallery.docKindOf("km.xlsx"))
    }

    @Test
    fun `doc badge falls back when there is no usable extension`() {
        assertEquals("FILE", Gallery.docKindOf(null))
        assertEquals("FILE", Gallery.docKindOf("noextension"))
        // A dotted name whose tail is prose, not an extension, would overflow
        // the tile — fall back rather than render it.
        assertEquals("FILE", Gallery.docKindOf("report.final.verylongext"))
    }

    // -------------------------------------------------------------- count copy

    @Test
    fun `count copy is singular for one and plural above`() {
        assertEquals("1 document", Gallery.countLabel(Gallery.Tab.DOCUMENTS, 1))
        assertEquals("4 documents", Gallery.countLabel(Gallery.Tab.DOCUMENTS, 4))
        assertEquals("1 photo or video", Gallery.countLabel(Gallery.Tab.MEDIA, 1))
        assertEquals("No links yet", Gallery.countLabel(Gallery.Tab.LINKS, 0))
    }
}

class GalleryGroupingTest {

    @Test
    fun `groups a run of the same day into one section`() {
        val rows = listOf("Today", "Today", "Yesterday", "Yesterday", "10 Aug")
        val sections = Gallery.groupByDay(rows) { it }
        assertEquals(listOf("Today", "Yesterday", "10 Aug"), sections.map { it.first })
        assertEquals(listOf(2, 2, 1), sections.map { it.second.size })
    }

    @Test
    fun `keeps the incoming order inside a section`() {
        val rows = listOf("a" to "Today", "b" to "Today")
        val sections = Gallery.groupByDay(rows) { it.second }
        assertEquals(listOf("a", "b"), sections.single().second.map { it.first })
    }

    @Test
    fun `a day that recurs after another day starts a new section`() {
        // Defends the contiguous-run assumption: if the caller ever hands over
        // unsorted rows, this must not silently merge two separate days into
        // one section — it splits, which is visible and therefore fixable.
        val rows = listOf("Today", "Yesterday", "Today")
        val sections = Gallery.groupByDay(rows) { it }
        assertEquals(3, sections.size)
    }

    @Test
    fun `empty input yields no sections`() {
        assertTrue(Gallery.groupByDay(emptyList<String>()) { it }.isEmpty())
    }
}

class GalleryLinkDisplayTest {

    @Test
    fun `host drops the scheme and www`() {
        assertEquals("naarni.com", Gallery.hostOf("https://www.naarni.com/report/12"))
        assertEquals("naarni.com", Gallery.hostOf("http://naarni.com"))
    }

    @Test
    fun `host drops the path and the query`() {
        assertEquals("docs.google.com", Gallery.hostOf("https://docs.google.com/a/b?c=d"))
    }

    @Test
    fun `a bare host survives unchanged`() {
        assertEquals("naarni.com", Gallery.hostOf("www.naarni.com"))
    }

    @Test
    fun `initial is the first alphanumeric of the host`() {
        assertEquals("N", Gallery.linkInitial("https://www.naarni.com"))
        assertEquals("D", Gallery.linkInitial("https://docs.google.com/x"))
    }
}
