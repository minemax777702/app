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

/** Draft roles describe intended permissions, never GitHub or runtime authorization. */
internal enum class CloudGroupRole(val wire: Int) {
    OWNER(1), MEMBER(2), VIEWER(3);

    companion object {
        fun fromWire(value: Int): CloudGroupRole =
            entries.firstOrNull { it.wire == value } ?: error("Unknown group role")
    }
}

internal data class CloudGroupDraft(
    val id: String,
    val name: String,
    val members: Map<String, CloudGroupRole>,
    val archived: Boolean = false,
) {
    init {
        require(UUID.fromString(id).toString() == id)
        require(name == name.trim() && name.isNotEmpty() && name.length <= 50 && name.none { it.isISOControl() })
        require(members.size in 1..CloudUserRegistry.MAX_USERS)
        require(members.keys.all { UUID.fromString(it).toString() == it })
        require(members.values.count { it == CloudGroupRole.OWNER } == 1) { "В группе должен быть один владелец" }
    }
}

/** A verified public-key registration draft, without transport access. */
internal data class CloudDeviceDraft(
    val userId: String,
    val publicKey: String,
    val registeredAt: Long,
    val revoked: Boolean = false,
) {
    init {
        require(UUID.fromString(userId).toString() == userId)
        CloudDeviceProtocol.publicKey(publicKey)
        require(registeredAt > 0)
    }
    val fingerprint: String get() = CloudDeviceProtocol.fingerprint(publicKey)
}

internal data class CloudUserRegistry(
    val revision: Long = 0,
    val users: List<CloudUserDraft> = emptyList(),
    val groups: List<CloudGroupDraft> = emptyList(),
    val devices: List<CloudDeviceDraft> = emptyList(),
) {
    init {
        require(revision >= 0)
        require(users.size <= MAX_USERS && users.map { it.id }.distinct().size == users.size)
        require(revision > 0 || (users.isEmpty() && groups.isEmpty() && devices.isEmpty()))
        require(groups.size <= MAX_GROUPS && groups.map { it.id }.distinct().size == groups.size)
        require(groups.sumOf { it.members.size } <= MAX_MEMBERSHIPS) { "Достигнут предел числа участников групп" }
        val byId = users.associateBy { it.id }
        require(devices.size <= MAX_DEVICES && devices.map { it.fingerprint }.distinct().size == devices.size)
        require(devices.all { it.userId in byId })
        require(devices.all { it.revoked || !byId.getValue(it.userId).archived }) { "Сначала отзовите устройства пользователя" }
        require(groups.all { group -> group.members.keys.all { it in byId } }) { "Неизвестный участник группы" }
        require(groups.all { group -> group.archived || group.members.keys.all { byId.getValue(it).archived.not() } }) {
            "Сначала исключите пользователя из активных групп или архивируйте эти группы"
        }
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

    fun saveGroup(id: String?, name: String, members: Map<String, CloudGroupRole>): CloudUserRegistry {
        val previous = id?.let { key -> groups.firstOrNull { it.id == key } ?: error("Group missing") }
        require(previous?.archived != true) { "Сначала восстановите группу" }
        val updated = CloudGroupDraft(id ?: UUID.randomUUID().toString(), name.trim(), members.toMap())
        return copy(revision = Math.addExact(revision, 1),
            groups = if (previous == null) groups + updated else groups.map { if (it.id == id) updated else it })
    }

    fun archiveGroup(id: String, archived: Boolean): CloudUserRegistry {
        require(groups.any { it.id == id })
        return copy(revision = Math.addExact(revision, 1),
            groups = groups.map { if (it.id == id) it.copy(archived = archived) else it })
    }

    fun registerDevice(userId: String, request: CloudDeviceRequest, now: Long): CloudUserRegistry {
        CloudDeviceProtocol.requireFresh(request, now)
        require(users.any { it.id == userId && !it.archived }) { "Пользователь недоступен" }
        require(devices.none { it.fingerprint == request.fingerprint }) { "Устройство уже зарегистрировано или отозвано" }
        return copy(revision = Math.addExact(revision, 1),
            devices = devices + CloudDeviceDraft(userId, request.publicKey, now))
    }

    fun revokeDevice(fingerprint: String): CloudUserRegistry {
        require(devices.any { it.fingerprint == fingerprint && !it.revoked })
        return copy(revision = Math.addExact(revision, 1),
            devices = devices.map { if (it.fingerprint == fingerprint) it.copy(revoked = true) else it })
    }

    companion object {
        const val MAX_DEVICES = 40
        const val MAX_USERS = 100
        const val MAX_GROUPS = 20
        const val MAX_MEMBERSHIPS = 1000
    }
}

/** Versioned bounded format, independent of Android and JSON. */
internal object CloudRegistryCodec {
    private const val MAGIC_V1 = 0x42554331
    private const val MAGIC_V2 = 0x42554332
    private const val MAGIC_V3 = 0x42554333
    const val MAX_BYTES = 65_536

    fun encode(registry: CloudUserRegistry): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeInt(MAGIC_V3)
            data.writeLong(registry.revision)
            data.writeInt(registry.users.size)
            registry.users.forEach {
                data.writeUTF(it.id)
                data.writeUTF(it.name)
                data.writeBoolean(it.archived)
            }
            data.writeInt(registry.groups.size)
            registry.groups.forEach { group ->
                data.writeUTF(group.id)
                data.writeUTF(group.name)
                data.writeBoolean(group.archived)
                data.writeInt(group.members.size)
                group.members.toSortedMap().forEach { (id, role) ->
                    data.writeUTF(id)
                    data.writeByte(role.wire)
                }
            }
            data.writeInt(registry.devices.size)
            registry.devices.forEach {
                data.writeUTF(it.userId)
                data.writeUTF(it.publicKey)
                data.writeLong(it.registeredAt)
                data.writeBoolean(it.revoked)
            }
        }
        return out.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): CloudUserRegistry {
        require(bytes.size in 16..MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            val magic = data.readInt()
            require(magic == MAGIC_V1 || magic == MAGIC_V2 || magic == MAGIC_V3)
            val revision = data.readLong()
            val count = data.readInt()
            require(count in 0..CloudUserRegistry.MAX_USERS)
            val users = List(count) { CloudUserDraft(data.readUTF(), data.readUTF(), data.readBoolean()) }
            val groups = if (magic == MAGIC_V1) emptyList() else {
                val groupCount = data.readInt()
                require(groupCount in 0..CloudUserRegistry.MAX_GROUPS)
                var totalMembers = 0
                List(groupCount) {
                    val id = data.readUTF()
                    val name = data.readUTF()
                    val archived = data.readBoolean()
                    val memberCount = data.readInt()
                    require(memberCount in 1..CloudUserRegistry.MAX_USERS)
                    totalMembers += memberCount
                    require(totalMembers <= CloudUserRegistry.MAX_MEMBERSHIPS)
                    val members = linkedMapOf<String, CloudGroupRole>()
                    repeat(memberCount) {
                        val userId = data.readUTF()
                        val role = CloudGroupRole.fromWire(data.readUnsignedByte())
                        require(members.put(userId, role) == null)
                    }
                    CloudGroupDraft(id, name, members, archived)
                }
            }
            val devices = if (magic != MAGIC_V3) emptyList() else {
                val countDevices = data.readInt()
                require(countDevices in 0..CloudUserRegistry.MAX_DEVICES)
                List(countDevices) { CloudDeviceDraft(data.readUTF(), data.readUTF(), data.readLong(), data.readBoolean()) }
            }
            require(data.read() == -1)
            CloudUserRegistry(revision, users, groups, devices)
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
