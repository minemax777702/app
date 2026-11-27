package com.mok.buylist.cloud

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class CloudUserRegistryTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    @Test fun editsPreserveIdentityAndRevision() {
        val added = CloudUserRegistry().add(" Анна ")
        val id = added.users.single().id
        val renamed = added.rename(id, "Мария")
        val archived = renamed.archive(id, true)
        assertEquals(3L, archived.revision)
        assertEquals(id, archived.users.single().id)
        assertEquals("Мария", archived.users.single().name)
        assertTrue(archived.users.single().archived)
        assertFalse(archived.archive(id, false).users.single().archived)
        assertEquals(archived, CloudRegistryCodec.decode(CloudRegistryCodec.encode(archived)))
    }

    @Test fun encryptedRegistryAuthenticatesContentAndUsesRandomNonce() {
        val registry = CloudUserRegistry().add("Анна")
        val a = CloudRegistryCrypto.encrypt(key, registry)
        val b = CloudRegistryCrypto.encrypt(key, registry)
        assertFalse(a.contentEquals(b))
        assertEquals(registry, CloudRegistryCrypto.decrypt(key, a))
        val tampered = a.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertTrue(runCatching { CloudRegistryCrypto.decrypt(key, tampered) }.isFailure)
        assertTrue(runCatching { CloudRegistryCrypto.decrypt(SecretKeySpec(ByteArray(32), "AES"), a) }.isFailure)
    }

    @Test fun malformedRecordsAreRejected() {
        val registry = CloudUserRegistry().add("Анна")
        assertTrue(runCatching { registry.add(" ") }.isFailure)
        assertTrue(runCatching { registry.rename("missing", "Борис") }.isFailure)
        assertTrue(runCatching { CloudRegistryCodec.decode(CloudRegistryCodec.encode(registry) + byteArrayOf(0)) }.isFailure)
    }
}
