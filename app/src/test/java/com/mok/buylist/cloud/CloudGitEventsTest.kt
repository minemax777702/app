package com.mok.buylist.cloud

import com.mok.buylist.sync.Snapshot
import org.eclipse.jgit.api.Git
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

class CloudGitEventsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun twoGitRepositoriesRoundTripAndRetrySignedEvents() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val publicKey = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(keys.public.encoded)
        val device = CloudDeviceProtocol.fingerprint(publicKey)
        val space = CloudSpace(CloudSpaceKind.PERSONAL, UUID.randomUUID().toString())
        val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        fun event(seq: Long, previous: String) = CloudSignedEvent.seal(
            CloudEventHeader(space, 1, 1, device, seq, previous), Snapshot(), key, keys.private)
        Git.init().setBare(true).setDirectory(folder.newFolder("remote")).call().use { remote ->
            Git.init().setBare(true).setDirectory(folder.newFolder("a")).call().use { a ->
                Git.init().setBare(true).setDirectory(folder.newFolder("b")).call().use { b ->
                    val uri = remote.repository.directory.toURI().toString()
                    val writer = CloudGitEvents(a.repository, uri)
                    val reader = CloudGitEvents(b.repository, uri)
                    val first = event(1, "")
                    assertTrue(reader.readAfter(space, 1, device, CloudCursor()).isEmpty())
                    writer.append(first)
                    writer.append(first)
                    assertEquals(listOf(first.digest), reader.readAfter(space, 1, device, CloudCursor()).map { it.digest })
                    val second = event(2, first.digest)
                    writer.append(second)
                    assertEquals(listOf(second.digest), reader.readAfter(space, 1, device, CloudCursor(1, first.digest)).map { it.digest })
                    assertTrue(runCatching { writer.append(event(2, first.digest)) }.isFailure)
                    assertTrue(runCatching { reader.readAfter(space, 1, device, CloudCursor(1, "0".repeat(64))) }.isFailure)
                    assertTrue(reader.readAfter(space, 1, device, CloudCursor(2, second.digest)).isEmpty())
                }
            }
        }
    }
}
