package com.mok.buylist.cloud

import com.mok.buylist.sync.Snapshot
import com.mok.buylist.sync.SyncItem
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

class CloudJournalTest {
    private fun pair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private fun publicKey(pair: KeyPair) = Base64.getUrlEncoder().withoutPadding().encodeToString(pair.public.encoded)
    private val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")

    @Test fun wireAndJournalRoundTripPreserveCursorAndWorkspace() {
        val admin = pair()
        val device = pair()
        val user = UUID.randomUUID().toString()
        val space = CloudSpace(CloudSpaceKind.PERSONAL, user)
        val grant = CloudGrant(user, publicKey(device), CloudGroupRole.OWNER)
        val manifest = SignedCloudManifest.sign(CloudManifest(space, 1, 1, listOf(grant)), admin.private)
        val decoded = SignedCloudManifest.decode(manifest.encode())
        val initial = CloudJournalRecord.initialize(space, decoded, publicKey(admin))
        val item = SyncItem("Молоко", 1, 2, false, user)
        val sealed = CloudSignedEvent.seal(CloudEventHeader(space, 1, 1, grant.deviceId, 1, ""), Snapshot(items = listOf(item)), key, device.private)
        val event = CloudSignedEvent.decode(sealed.encode())
        assertEquals(sealed.digest, event.digest)
        val applied = initial.accept(event, decoded, key)
        val file = CloudJournalCodec.encrypt(applied, key)
        val reopened = CloudJournalCodec.decrypt(space, file, key)
        assertEquals(applied, reopened)
        assertEquals(listOf(item), reopened.state.snapshot.items)
        assertEquals(reopened, reopened.accept(event, decoded, key))
        val other = CloudSpace(CloudSpaceKind.GROUP, space.id)
        assertTrue(runCatching { CloudJournalCodec.decrypt(other, file, key) }.isFailure)
        val corrupt = file.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertTrue(runCatching { CloudJournalCodec.decrypt(space, corrupt, key) }.isFailure)
        assertTrue(runCatching { CloudSignedEvent.decode(sealed.encode() + byteArrayOf(0)) }.isFailure)
        assertTrue(runCatching { SignedCloudManifest.decode(manifest.encode().dropLast(1).toByteArray()) }.isFailure)
    }

    @Test fun differentSignedManifestAtSameRevisionCannotReplacePinnedRights() {
        val admin = pair()
        val one = pair()
        val two = pair()
        val user = UUID.randomUUID().toString()
        val space = CloudSpace(CloudSpaceKind.PERSONAL, user)
        val first = CloudGrant(user, publicKey(one), CloudGroupRole.OWNER)
        val second = CloudGrant(user, publicKey(two), CloudGroupRole.OWNER)
        val original = SignedCloudManifest.sign(CloudManifest(space, 1, 1, listOf(first)), admin.private)
        val conflict = SignedCloudManifest.sign(CloudManifest(space, 1, 1, listOf(second)), admin.private)
        val record = CloudJournalRecord.initialize(space, original, publicKey(admin))
        val event = CloudSignedEvent.seal(CloudEventHeader(space, 1, 1, second.deviceId, 1, ""), Snapshot(), key, two.private)
        assertTrue(runCatching { record.accept(event, conflict, key) }.isFailure)
    }
}
