package com.mok.buylist.cloud

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class CloudDeviceProtocolTest {
    private fun pair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val now = 1_800_000_000_000L

    @Test fun signedQrExpiresAndCannotBeReplayedIntoRegistry() {
        val pair = pair()
        val qr = CloudDeviceProtocol.create(pair.public, pair.private, now)
        val request = CloudDeviceProtocol.parse(qr, now)
        assertEquals(64, request.fingerprint.length)
        assertTrue(runCatching { CloudDeviceProtocol.parse(qr, now + CloudDeviceProtocol.TTL_MILLIS + 1) }.isFailure)
        val initial = CloudUserRegistry().add("Тест")
        val user = initial.users.single().id
        val registered = initial.registerDevice(user, request, now)
        assertEquals(registered, CloudRegistryCodec.decode(CloudRegistryCodec.encode(registered)))
        assertTrue(runCatching { registered.registerDevice(user, request, now) }.isFailure)
        assertTrue(runCatching { registered.archive(user, true) }.isFailure)
        val revoked = registered.revokeDevice(request.fingerprint)
        assertTrue(revoked.archive(user, true).users.single().archived)
        assertTrue(runCatching { revoked.registerDevice(user, request, now) }.isFailure)
    }

    @Test fun changedTimestampOrWrongSigningKeyIsRejected() {
        val keys = pair()
        val request = CloudDeviceProtocol.parse(CloudDeviceProtocol.create(keys.public, keys.private, now), now)
        assertTrue(runCatching { CloudDeviceProtocol.requireFresh(request.copy(createdAt = now - 1), now) }.isFailure)
        val other = pair()
        val qr = CloudDeviceProtocol.create(keys.public, other.private, now)
        assertTrue(runCatching { CloudDeviceProtocol.parse(qr, now) }.isFailure)
    }
}
