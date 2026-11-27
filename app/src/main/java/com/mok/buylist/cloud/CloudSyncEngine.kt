package com.mok.buylist.cloud

import com.mok.buylist.sync.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.KeyPair
import java.util.Base64
import javax.crypto.SecretKey

/**
 * Explicit space only. Never exports or imports the application's default Room database.
 * Caller must supply an authenticated current manifest, content key and explicit local delta.
 */
internal class CloudSyncEngine(
    private val journals: CloudJournalStore,
    private val outbox: CloudOutbox,
    private val transport: CloudEventTransport,
    private val identity: KeyPair,
) {
    private val device = CloudDeviceProtocol.fingerprint(Base64.getUrlEncoder().withoutPadding().encodeToString(identity.public.encoded))

    suspend fun flush(space: CloudSpace, signed: SignedCloudManifest, key: SecretKey): CloudJournalRecord =
        withContext(Dispatchers.IO) { LOCK.withLock { flushLocked(space, signed, key) } }

    private suspend fun flushLocked(space: CloudSpace, signed: SignedCloudManifest, key: SecretKey): CloudJournalRecord {
        val record = journals.read(space)
        val manifest = signed.verify(record.pinnedAdmin, space, record.state.manifestRevision)
        require(manifest.epoch == record.state.epoch)
        require(manifest.grants.any { it.deviceId == device }) { "Device is not a member" }
        val event = outbox.pending(space, device) ?: return record
        require(manifest.grants.any { it.deviceId == device && it.canWrite })
        val cursor = record.state.cursors[device] ?: CloudCursor()
        if (cursor.sequence == event.header.sequence && cursor.digest == event.digest) {
            outbox.acknowledge(space, device, cursor)
            return record
        }
        // Verify fully before sending; do not acknowledge until durable state is committed.
        record.accept(event, signed, key)
        transport.append(event)
        val accepted = journals.accept(space, event.encode(), signed.encode(), key)
        outbox.acknowledge(space, device, accepted.state.cursors.getValue(device))
        return accepted
    }

    suspend fun publish(space: CloudSpace, delta: Snapshot, signed: SignedCloudManifest, key: SecretKey): CloudJournalRecord =
        withContext(Dispatchers.IO) {
            LOCK.withLock {
                val record = flushLocked(space, signed, key)
                val manifest = signed.verify(record.pinnedAdmin, space, record.state.manifestRevision)
                require(manifest.grants.any { it.deviceId == device && it.canWrite }) { "Read-only workspace" }
                if (delta == Snapshot()) return@withLock record
                val cursor = record.state.cursors[device] ?: CloudCursor()
                val event = CloudSignedEvent.seal(CloudEventHeader(space, manifest.epoch, manifest.revision, device,
                    Math.addExact(cursor.sequence, 1), cursor.digest), delta, key, identity.private)
                record.accept(event, signed, key)
                outbox.stage(event)
                flushLocked(space, signed, key)
            }
        }

    suspend fun pull(space: CloudSpace, signed: SignedCloudManifest, key: SecretKey): CloudJournalRecord =
        withContext(Dispatchers.IO) {
            LOCK.withLock {
                var record = flushLocked(space, signed, key)
                val manifest = signed.verify(record.pinnedAdmin, space, record.state.manifestRevision)
                for (grant in manifest.grants.filter { it.canWrite }) {
                    val cursor = record.state.cursors[grant.deviceId] ?: CloudCursor()
                    for (event in transport.readAfter(space, manifest.epoch, grant.deviceId, cursor)) {
                        record = journals.accept(space, event.encode(), signed.encode(), key)
                    }
                }
                record
            }
        }

    private companion object { val LOCK = Mutex() }
}
