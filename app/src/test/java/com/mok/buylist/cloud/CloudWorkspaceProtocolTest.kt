package com.mok.buylist.cloud

import com.mok.buylist.sync.Snapshot
import com.mok.buylist.sync.SyncItem
import com.mok.buylist.sync.SyncTomb
import com.mok.buylist.sync.TombKind
import com.mok.buylist.sync.SyncMerge
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

class CloudWorkspaceProtocolTest {
    private fun pair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun encoded(pair: KeyPair) = Base64.getUrlEncoder().withoutPadding().encodeToString(pair.public.encoded)
    private val aes = SecretKeySpec(ByteArray(32) { 42 }, "AES")

    @Test fun signedDeltasAreScopedAndReplaySafe() {
        val admin = pair()
        val device = pair()
        val user = UUID.randomUUID().toString()
        val space = CloudSpace(CloudSpaceKind.PERSONAL, user)
        val grant = CloudGrant(user, encoded(device), CloudGroupRole.OWNER)
        val manifest = SignedCloudManifest.sign(CloudManifest(space, 1, 1, listOf(grant)), admin.private)
        val initial = CloudWorkspaceState(space, 1, 1)
        val item = SyncItem("Молоко", 1, 2, false, user)
        val event = CloudSignedEvent.seal(CloudEventHeader(space, 1, 1, grant.deviceId, 1, ""),
            Snapshot(items = listOf(item)), aes, device.private)
        val applied = initial.accept(event, manifest, encoded(admin), aes)
        assertEquals(listOf(item), applied.snapshot.items)
        assertEquals(applied, applied.accept(event, manifest, encoded(admin), aes))
        assertTrue(runCatching { initial.accept(event, manifest, encoded(pair()), aes) }.isFailure)
        val other = initial.copy(space = CloudSpace(CloudSpaceKind.GROUP, UUID.randomUUID().toString()))
        assertTrue(runCatching { other.accept(event, manifest, encoded(admin), aes) }.isFailure)
        assertTrue(runCatching { initial.accept(event, manifest, encoded(admin), SecretKeySpec(ByteArray(32), "AES")) }.isFailure)
        val deleted = CloudSignedEvent.seal(CloudEventHeader(space, 1, 1, grant.deviceId, 2, event.digest),
            Snapshot(tombs = listOf(SyncTomb(TombKind.ITEM, item.key, 3))), aes, device.private)
        assertTrue(runCatching { initial.accept(deleted, manifest, encoded(admin), aes) }.isFailure)
        val afterDelete = applied.accept(deleted, manifest, encoded(admin), aes)
        assertTrue(afterDelete.snapshot.items.isEmpty())
        assertTrue(runCatching { afterDelete.accept(event, manifest, encoded(admin), aes) }.isFailure)
    }

    @Test fun viewersRevokedKeysAndWrongSignaturesCannotWrite() {
        val admin = pair()
        val owner = pair()
        val viewer = pair()
        val space = CloudSpace(CloudSpaceKind.GROUP, UUID.randomUUID().toString())
        val ownerGrant = CloudGrant(UUID.randomUUID().toString(), encoded(owner), CloudGroupRole.OWNER)
        val viewerGrant = CloudGrant(UUID.randomUUID().toString(), encoded(viewer), CloudGroupRole.VIEWER)
        val manifest = SignedCloudManifest.sign(CloudManifest(space, 1, 2, listOf(ownerGrant, viewerGrant)), admin.private)
        val state = CloudWorkspaceState(space, 1, 2)
        val viewerEvent = CloudSignedEvent.seal(CloudEventHeader(space, 1, 2, viewerGrant.deviceId, 1, ""), Snapshot(), aes, viewer.private)
        assertTrue(runCatching { state.accept(viewerEvent, manifest, encoded(admin), aes) }.isFailure)
        val spoofed = CloudSignedEvent.seal(CloudEventHeader(space, 1, 2, ownerGrant.deviceId, 1, ""), Snapshot(), aes, viewer.private)
        assertTrue(runCatching { state.accept(spoofed, manifest, encoded(admin), aes) }.isFailure)
        val stale = SignedCloudManifest.sign(CloudManifest(space, 1, 1, listOf(ownerGrant)), admin.private)
        assertTrue(runCatching { stale.verify(encoded(admin), space, 2) }.isFailure)
        val revoked = SignedCloudManifest.sign(CloudManifest(space, 2, 3, listOf(ownerGrant)), admin.private)
        assertTrue(runCatching { state.accept(viewerEvent, revoked, encoded(admin), aes) }.isFailure)
    }

    @Test fun deltasCarryChangedRowsAndExplicitTombstones() {
        val first = SyncItem("Хлеб", 1, 2, false, "test")
        val second = SyncItem("Молоко", 1, 2, false, "test")
        val before = Snapshot(items = listOf(first, second))
        val updated = first.copy(quantity = 2, at = 4)
        val after = Snapshot(items = listOf(updated), tombs = listOf(SyncTomb(TombKind.ITEM, second.key, 5)))
        val delta = CloudSnapshotDelta.between(before, after)
        assertEquals(listOf(updated), delta.items)
        assertEquals(after.tombs, delta.tombs)
        assertEquals(SyncMerge.merge(before, after), SyncMerge.merge(before, delta))
        assertEquals(Snapshot(), CloudSnapshotDelta.between(after, after))
    }
}
