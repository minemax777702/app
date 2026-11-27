package com.mok.buylist.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mok.buylist.sync.Snapshot
import com.mok.buylist.sync.SyncItem
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class CloudGitDeliveryTest {
    @Test fun interruptedDeliveryResumesWithoutDuplicate() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun pair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        fun pub(p: java.security.KeyPair) = Base64.getUrlEncoder().withoutPadding().encodeToString(p.public.encoded)
        val admin = pair()
        val a = pair()
        val b = pair()
        val user = UUID.randomUUID().toString()
        val space = CloudSpace(CloudSpaceKind.PERSONAL, user)
        val signed = SignedCloudManifest.sign(CloudManifest(space, 1, 1, listOf(
            CloudGrant(user, pub(a), CloudGroupRole.OWNER), CloudGrant(user, pub(b), CloudGroupRole.OWNER))), admin.private)
        val key = SecretKeySpec(ByteArray(32) { 9 }, "AES")
        val runA = UUID.randomUUID()
        val runB = UUID.randomUUID()
        val journalA = CloudJournalStore(context, runA)
        val journalB = CloudJournalStore(context, runB)
        val box = CloudOutbox(context, runA)
        val directory = File(context.cacheDir, "cloud-git-test-$runA")
        check(directory.mkdirs())
        try {
            journalA.initialize(space, signed, pub(admin))
            journalB.initialize(space, signed, pub(admin))
            Git.init().setBare(true).setDirectory(File(directory, "remote")).call().use { remote ->
                Git.init().setBare(true).setDirectory(File(directory, "a")).call().use { gitA ->
                    Git.init().setBare(true).setDirectory(File(directory, "b")).call().use { gitB ->
                        val uri = remote.repository.directory.toURI().toString()
                        val writer = CloudGitEvents(gitA.repository, uri)
                        val reader = CloudGitEvents(gitB.repository, uri)
                        val interrupted = object : CloudEventTransport by writer {
                            override fun append(event: CloudSignedEvent) {
                                writer.append(event)
                                error("Simulated loss of acknowledgment")
                            }
                        }
                        val item = SyncItem("Тест", 1, 2, false, user)
                        val engine = CloudSyncEngine(journalA, box, interrupted, a)
                        assertTrue(runCatching { engine.publish(space, Snapshot(items = listOf(item)), signed, key) }.isFailure)
                        val fingerprint = CloudDeviceProtocol.fingerprint(pub(a))
                        assertNotNull(box.pending(space, fingerprint))
                        val resumed = CloudSyncEngine(journalA, CloudOutbox(context, runA), writer, a)
                        val saved = resumed.flush(space, signed, key)
                        assertEquals(1L, saved.state.cursors.getValue(fingerprint).sequence)
                        assertNull(box.pending(space, fingerprint))
                        val other = CloudSyncEngine(journalB, CloudOutbox(context, runB), reader, b)
                        assertEquals(listOf(item), other.pull(space, signed, key).state.snapshot.items)
                        assertEquals(listOf(item), other.pull(space, signed, key).state.snapshot.items)
                    }
                }
            }
        } finally {
            journalA.cleanTest(space)
            journalB.cleanTest(space)
            directory.deleteRecursively()
            File(context.noBackupFilesDir, "cloud-outbox-tests/$runA").deleteRecursively()
            File(context.noBackupFilesDir, "cloud-outbox-tests/$runB").deleteRecursively()
        }
    }
}
