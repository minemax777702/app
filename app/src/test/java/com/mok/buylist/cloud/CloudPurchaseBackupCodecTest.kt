package com.mok.buylist.cloud

import org.junit.Assert.*
import org.junit.Test
import com.mok.buylist.sync.BackupCodec
import com.mok.buylist.sync.BackupContents
import com.mok.buylist.sync.Snapshot
import com.mok.buylist.sync.SyncUser

class CloudPurchaseBackupCodecTest {
    @Test fun encryptedBackupPreservesSnapshotAndRejectsTampering() {
        val backup = BackupContents("test-id", "Тест", Snapshot(users = listOf(SyncUser("test-id", "Тест", 1L))))
        val plain = BackupCodec.encode(backup)
        val password = "unique backup passphrase".toCharArray()
        val encrypted = CloudPurchaseBackupCodec.encrypt(plain, password)
        val decoded = BackupCodec.decode(CloudPurchaseBackupCodec.decrypt(encrypted, password))
        assertEquals(backup.ownerId, decoded.ownerId)
        assertEquals(backup.snapshot, decoded.snapshot)
        val corrupted = encrypted.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertTrue(runCatching { CloudPurchaseBackupCodec.decrypt(corrupted, password) }.isFailure)
        assertTrue(runCatching { CloudPurchaseBackupCodec.decrypt(encrypted, "different backup phrase".toCharArray()) }.isFailure)
        assertTrue(runCatching { CloudPurchaseBackupCodec.encrypt(ByteArray(CloudPurchaseBackupCodec.MAX_PAYLOAD_BYTES + 1), password) }.isFailure)
        password.fill('\u0000')
    }
}
