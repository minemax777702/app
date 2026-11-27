package com.mok.buylist.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseChangelogTest {
    @Test fun readableNotesStripMarkdownAndTechnicalComparisonLink() {
        val raw = "## Что нового\n- **Сортировка** товаров\n- [Подробнее](https://github.com/example)\n**Full Changelog**: https://github.com/example/compare/a...b"
        assertEquals(listOf("Что нового", "• Сортировка товаров", "• Подробнее"), releaseChangelogLines(raw))
    }

    @Test fun missingNotesStayEmpty() {
        assertEquals(emptyList<String>(), releaseChangelogLines(" \n\n"))
    }
}
