package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesMarkdownTest {
    @Test
    fun `release notes parser renders headings lists callouts and narrow tables`() {
        val markdown =
            """
            ## What's changed

            - Fixed **Open RSS subscriptions**.

            > [!IMPORTANT]
            > **Update immediately.** Do not use older versions.

            | Variant | Download |
            | --- | --- |
            | FOSS | [Download](https://example.com/foss.apk) |
            """.trimIndent()

        val blocks = parseReleaseNotesMarkdown(markdown)

        assertEquals(ReleaseNoteBlockType.HEADING, blocks[0].type)
        assertEquals(ReleaseNoteBlockType.BULLET, blocks[1].type)
        assertEquals(ReleaseNoteBlockType.IMPORTANT, blocks[2].type)
        assertEquals(ReleaseNoteBlockType.TABLE_HEADER, blocks[3].type)
        assertEquals(ReleaseNoteBlockType.TABLE_ROW, blocks[4].type)
        assertFalse(blocks.any { '|' in it.text || "[!IMPORTANT]" in it.text })
        assertEquals("Variant  \u00B7  Download", blocks[3].text)
    }

    @Test
    fun `inline parser preserves text and exposes supported markdown styles`() {
        val inline = parseReleaseNoteInline(
            "Use **bold**, *italic*, `code`, and [GitHub](https://github.com/RizkLee/MetroVerse).",
        )

        assertTrue(inline.any { it.style == ReleaseNoteInlineStyle.BOLD && it.text == "bold" })
        assertTrue(inline.any { it.style == ReleaseNoteInlineStyle.ITALIC && it.text == "italic" })
        assertTrue(inline.any { it.style == ReleaseNoteInlineStyle.CODE && it.text == "code" })
        assertTrue(
            inline.any {
                it.style == ReleaseNoteInlineStyle.LINK &&
                    it.text == "GitHub" &&
                    it.url == "https://github.com/RizkLee/MetroVerse"
            },
        )
        assertEquals(
            "Use bold, italic, code, and GitHub.",
            inline.joinToString(separator = "", transform = ReleaseNoteInline::text),
        )
    }

    @Test
    fun `malformed markdown remains readable and insecure links stay plain text`() {
        val markdown = "Ordinary A | B | C prose with **unfinished formatting"
        val blocks = parseReleaseNotesMarkdown(markdown)
        val inline = parseReleaseNoteInline("[Unsafe](http://example.com) and **unfinished")

        assertEquals(listOf(ReleaseNoteBlockType.PARAGRAPH), blocks.map(ReleaseNoteBlock::type))
        assertEquals(markdown, blocks.single().text)
        assertFalse(inline.any { it.style == ReleaseNoteInlineStyle.LINK })
        assertEquals("[Unsafe](http://example.com) and **unfinished", inline.joinToString("") { it.text })
    }
}
