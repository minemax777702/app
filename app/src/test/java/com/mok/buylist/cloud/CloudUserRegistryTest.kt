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

    @Test fun groupsPreserveRolesAndRequireExactlyOneOwner() {
        val initial = CloudUserRegistry().add("Анна").add("Борис").add("Вера")
        val (anna, boris, vera) = initial.users.map { it.id }
        val roles = mapOf(anna to CloudGroupRole.OWNER, boris to CloudGroupRole.MEMBER, vera to CloudGroupRole.VIEWER)
        val created = initial.saveGroup(null, "Семья", roles)
        val group = created.groups.single()
        assertEquals(roles, group.members)
        assertEquals(created, CloudRegistryCrypto.decrypt(key, CloudRegistryCrypto.encrypt(key, created)))
        assertTrue(runCatching { created.saveGroup(group.id, "Семья", mapOf(anna to CloudGroupRole.MEMBER)) }.isFailure)
        assertTrue(runCatching { created.saveGroup(group.id, "Семья", mapOf(anna to CloudGroupRole.OWNER, boris to CloudGroupRole.OWNER)) }.isFailure)
        val transferred = created.saveGroup(group.id, "Дом", roles + mapOf(anna to CloudGroupRole.MEMBER, boris to CloudGroupRole.OWNER))
        assertEquals(group.id, transferred.groups.single().id)
        assertEquals(CloudGroupRole.OWNER, transferred.groups.single().members[boris])
    }

    @Test fun archivedUserCannotBelongToActiveGroup() {
        val initial = CloudUserRegistry().add("Анна").add("Борис")
        val (anna, boris) = initial.users.map { it.id }
        val created = initial.saveGroup(null, "Семья", mapOf(anna to CloudGroupRole.OWNER, boris to CloudGroupRole.VIEWER))
        val id = created.groups.single().id
        assertTrue(runCatching { created.archive(boris, true) }.isFailure)
        val archived = created.archiveGroup(id, true).archive(boris, true)
        assertTrue(runCatching { archived.archiveGroup(id, false) }.isFailure)
        assertFalse(archived.archive(boris, false).archiveGroup(id, false).groups.single().archived)
        assertTrue(runCatching { initial.saveGroup(null, "Дом", mapOf(java.util.UUID.randomUUID().toString() to CloudGroupRole.OWNER)) }.isFailure)
    }

    @Test fun oldRegistryPayloadCanBeReadWithoutLosingUsers() {
        val initial = CloudUserRegistry().add("Анна")
        val user = initial.users.single()
        val output = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(output).use {
            it.writeInt(0x42554331)
            it.writeLong(initial.revision)
            it.writeInt(1)
            it.writeUTF(user.id)
            it.writeUTF(user.name)
            it.writeBoolean(false)
        }
        val old = CloudRegistryCodec.decode(output.toByteArray())
        assertEquals(initial, old)
        assertEquals(old, CloudRegistryCodec.decode(CloudRegistryCodec.encode(old)))
    }

    @Test fun malformedRecordsAreRejected() {
        val registry = CloudUserRegistry().add("Анна")
        assertTrue(runCatching { registry.add(" ") }.isFailure)
        assertTrue(runCatching { registry.rename("missing", "Борис") }.isFailure)
        assertTrue(runCatching { CloudRegistryCodec.decode(CloudRegistryCodec.encode(registry) + byteArrayOf(0)) }.isFailure)
    }
}
