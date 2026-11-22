package com.mok.buylist

import com.mok.buylist.sync.*
import org.junit.Assert.*
import org.junit.Test

class SnapshotSectionTest {
    @Test fun sectionsKeepOnlyTheirOwnDeletions() {
        val snapshot = Snapshot(
            users = listOf(SyncUser("u", "cheese", 1)),
            items = listOf(SyncItem("milk", 1, 2, false, "u")),
            archives = listOf(SyncArchive("a", 1, listOf("bread"), listOf("u"), 0, 0)),
            tombs = listOf(SyncTomb(TombKind.ITEM, "cheese", 2), SyncTomb(TombKind.ARCHIVE, "b", 3)),
        )
        val list = snapshot.selectSection(SnapshotSection.LIST)
        assertEquals(snapshot.items, list.items)
        assertTrue(list.archives.isEmpty())
        assertEquals(listOf(TombKind.ITEM), list.tombs.map { it.kind })
        val archive = snapshot.selectSection(SnapshotSection.ARCHIVE)
        assertTrue(archive.items.isEmpty())
        assertEquals(snapshot.archives, archive.archives)
        assertEquals(listOf(TombKind.ARCHIVE), archive.tombs.map { it.kind })
        assertEquals(snapshot.users, archive.users)
        assertEquals(snapshot, snapshot.selectSection(SnapshotSection.ALL))
        assertEquals(snapshot, SyncCodec.decode(SyncCodec.encode(snapshot)))
    }
}
