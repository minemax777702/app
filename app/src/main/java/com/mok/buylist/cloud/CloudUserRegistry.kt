package com.mok.buylist.cloud

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Administrative draft, not a permission or an activated account. */
internal data class CloudUserDraft(val id: String, val name: String, val archived: Boolean = false) {
    init {
        require(UUID.fromString(id).toString() == id)
        require(name == name.trim() && name.isNotEmpty() && name.length <= 50)
        require(name.none { it.isISOControl() })
    }
}

internal data class CloudUserRegistry(val revision: Long = 0, val users: List<CloudUserDraft> = emptyList()) {
    init {
        require(revision >= 0)
        require(users.size <= MAX_USERS && users.map { it.id }.distinct().size == users.size)
        require(revision > 0 || users.isEmpty())
    }

    fun add(name: String) = copy(
        revision = Math.addExact(revision, 1),
        users = users + CloudUserDraft(UUID.randomUUID().toString(), name.trim()),
    )

    fun rename(id: String, name: String) = change(id) { it.copy(name = name.trim()) }
    fun archive(id: String, archived: Boolean) = change(id) { it.copy(archived = archived) }

    private fun change(id: String, transform: (CloudUserDraft) -> CloudUserDraft): CloudUserRegistry {
        require(users.any { it.id == id })
        return copy(revision = Math.addExact(revision, 1),
            users = users.map { if (it.id == id) transform(it) else it })
    }

    companion object { const val MAX_USERS = 100 }
}

/** Versioned bounded format, independent of Android and JSON. */
internal object CloudRegistryCodec {
    private const val MAGIC = 0x42554331
    const val MAX_BYTES = 65_536

    fun encode(registry: CloudUserRegistry): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeInt(MAGIC)
            data.writeLong(registry.revision)
            data.writeInt(registry.users.size)
            registry.users.forEach {
                data.writeUTF(it.id)
                data.writeUTF(it.name)
                data.writeBoolean(it.archived)
            }
        }
        return out.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): CloudUserRegistry {
        require(bytes.size in 16..MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == MAGIC)
            val revision = data.readLong()
            val count = data.readInt()
            require(count in 0..CloudUserRegistry.MAX_USERS)
            val users = List(count) { CloudUserDraft(data.readUTF(), data.readUTF(), data.readBoolean()) }
            require(data.read() == -1)
            CloudUserRegistry(revision, users)
        }
    }
}

/** Authenticated encryption bound to the repository and this draft format. */
internal object CloudRegistryCrypto {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val aad = "BuyList|minemax777702/app-cloud|admin-users|1".toByteArray(Charsets.UTF_8)

    fun encrypt(key: SecretKey, registry: CloudUserRegistry): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        require(cipher.iv.size == 12)
        cipher.updateAAD(aad)
        val plain = CloudRegistryCodec.encode(registry)
        return try { byteArrayOf(1) + cipher.iv + cipher.doFinal(plain) }
        finally { plain.fill(0) }
    }

    fun decrypt(key: SecretKey, bytes: ByteArray): CloudUserRegistry {
        require(bytes.size in 29..CloudRegistryCodec.MAX_BYTES + 29 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(aad)
        val plain = cipher.doFinal(bytes, 13, bytes.size - 13)
        return try { CloudRegistryCodec.decode(plain) } finally { plain.fill(0) }
    }
}
