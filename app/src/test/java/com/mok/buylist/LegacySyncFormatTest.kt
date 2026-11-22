package com.mok.buylist

import com.mok.buylist.sync.ByteWriter
import com.mok.buylist.sync.SyncCodec
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacySyncFormatTest {
    @Test
    fun oldPriceFieldsAreSkippedWithoutLosingOtherData() {
        val w = ByteWriter()
        w.byte(1)
        w.int(0) // users
        w.int(1); w.string("Хлеб"); w.long(1); w.long(2); w.byte(0); w.string("u")
        w.int(0) // history
        w.int(1); w.string("Хлеб"); w.long(4500); w.string("pcs"); w.long(3)
        w.int(0) // hidden
        w.int(1); w.byte(0); w.string("archive"); w.long(4); w.long(5); w.int(1); w.long(6)
        w.int(1); w.string("Хлеб"); w.string("u")
        w.int(1); w.string("Хлеб"); w.long(4500); w.raw(ByteArray(8)); w.string("pcs")
        w.int(2); w.byte(2); w.string("хлеб"); w.long(7)
        w.byte(0); w.string("сыр"); w.long(8)
        val compressed = ByteArrayOutputStream().also { out ->
            DeflaterOutputStream(out).use { it.write(w.toByteArray()) }
        }.toByteArray()

        val snapshot = SyncCodec.decode(compressed)
        assertEquals("Хлеб", snapshot.items.single().name)
        assertEquals(listOf("Хлеб"), snapshot.archives.single().names)
        assertEquals("сыр", snapshot.tombs.single().key)
        assertEquals(snapshot, SyncCodec.decode(SyncCodec.encode(snapshot)))
        assertTrue(snapshot.archives.single().buyers.contains("u"))
    }
}
