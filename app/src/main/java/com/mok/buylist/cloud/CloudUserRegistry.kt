package com.mok.buylist.cloud

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
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
    val encryptionPublicKey: String = "",
) {
    init {
        require(UUID.fromString(userId).toString() == userId)
        CloudDeviceProtocol.publicKey(publicKey)
        require(registeredAt > 0)
        if (encryptionPublicKey.isNotEmpty()) CloudInviteCrypto.encryptionKey(encryptionPublicKey)
    }
    val fingerprint: String get() = CloudDeviceProtocol.fingerprint(publicKey)
}

internal data class CloudUserRegistry(
    val revision: Long = 0,
    val users: List<CloudUserDraft> = emptyList(),
    val groups: List<CloudGroupDraft> = emptyList(),
    val devices: List<CloudDeviceDraft> = emptyList(),
    val adminSigner: CloudAdminSigner? = null,
    val provisions: List<CloudProvision> = emptyList(),
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
        require(provisions.size <= MAX_USERS + MAX_GROUPS && provisions.map { it.space }.distinct().size == provisions.size)
        require(provisions.isEmpty() || adminSigner != null)
        provisions.forEach { provision ->
            val manifest = provision.signed().verify(requireNotNull(adminSigner).publicKey, provision.space, 1)
            require(manifest.grants.sortedBy { it.deviceId } == grantsFor(provision.space)) {
                "Состав активированного списка нельзя менять без безопасной смены ключей"
            }
            provision.key()
        }
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
            devices = devices + CloudDeviceDraft(userId, request.publicKey, now, encryptionPublicKey = request.encryptionPublicKey))
    }

    fun registerNewUser(name: String, request: CloudDeviceRequest, now: Long): CloudUserRegistry {
        CloudDeviceProtocol.requireFresh(request, now)
        require(devices.none { it.fingerprint == request.fingerprint }) { "Устройство уже зарегистрировано" }
        val user = CloudUserDraft(UUID.randomUUID().toString(), name.trim())
        return copy(revision = Math.addExact(revision, 1),
            users = users + user,
            devices = devices + CloudDeviceDraft(user.id, request.publicKey, now, encryptionPublicKey = request.encryptionPublicKey))
    }

    fun updateDeviceEncryption(request: CloudDeviceRequest, now: Long): CloudUserRegistry {
        CloudDeviceProtocol.requireFresh(request, now)
        require(request.encryptionPublicKey.isNotEmpty())
        val device = devices.single { it.fingerprint == request.fingerprint && !it.revoked }
        if (device.encryptionPublicKey == request.encryptionPublicKey) return this
        return copy(revision = Math.addExact(revision, 1), devices = devices.map {
            if (it.fingerprint == device.fingerprint) it.copy(encryptionPublicKey = request.encryptionPublicKey) else it
        })
    }

    fun deleteDevice(fingerprint: String): CloudUserRegistry {
        require(devices.any { it.fingerprint == fingerprint }) { "Устройство не найдено" }
        return copy(revision = Math.addExact(revision, 1), devices = devices.filterNot { it.fingerprint == fingerprint })
    }

    fun deleteGroup(id: String): CloudUserRegistry {
        require(groups.any { it.id == id }) { "Группа не найдена" }
        return copy(revision = Math.addExact(revision, 1), groups = groups.filterNot { it.id == id })
    }

    /** Caller must revoke all matching remote deploy keys before saving. Never touches purchases. */
    fun deleteUser(id: String): CloudUserRegistry {
        require(users.any { it.id == id }) { "Пользователь не найден" }
        val owned = groups.filter { it.members[id] == CloudGroupRole.OWNER && it.members.size > 1 }
        require(owned.isEmpty()) {
            "Сначала передайте владение группами: " + owned.joinToString(", ") { it.name }
        }
        return copy(revision = Math.addExact(revision, 1),
            users = users.filterNot { it.id == id },
            devices = devices.filterNot { it.userId == id },
            groups = groups.mapNotNull { group ->
                if (id !in group.members) group
                else if (group.members.size == 1) null
                else group.copy(members = group.members - id)
            })
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
    private const val MAGIC_V4 = 0x42554334
    const val MAX_BYTES = 65_536

    fun encode(registry: CloudUserRegistry): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeInt(MAGIC_V4)
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
                data.writeUTF(it.encryptionPublicKey)
            }
            data.writeBoolean(registry.adminSigner != null)
            registry.adminSigner?.let { data.writeUTF(it.publicKey); data.writeUTF(it.privateKey) }
            data.writeInt(registry.provisions.size)
            registry.provisions.forEach { data.writeUTF(it.manifest); data.writeUTF(it.secret) }
        }
        return out.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): CloudUserRegistry {
        require(bytes.size in 16..MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            val magic = data.readInt()
            require(magic == MAGIC_V1 || magic == MAGIC_V2 || magic == MAGIC_V3 || magic == MAGIC_V4)
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
            val devices = if (magic != MAGIC_V3 && magic != MAGIC_V4) emptyList() else {
                val countDevices = data.readInt()
                require(countDevices in 0..CloudUserRegistry.MAX_DEVICES)
                List(countDevices) { CloudDeviceDraft(data.readUTF(), data.readUTF(), data.readLong(), data.readBoolean(), if (magic == MAGIC_V4) data.readUTF() else "") }
            }
            val signer = if (magic == MAGIC_V4 && data.readBoolean()) CloudAdminSigner(data.readUTF(), data.readUTF()) else null
            val provisions = if (magic == MAGIC_V4) {
                val n = data.readInt(); require(n in 0..(CloudUserRegistry.MAX_USERS + CloudUserRegistry.MAX_GROUPS))
                List(n) { CloudProvision(data.readUTF(), data.readUTF()) }
            } else emptyList()
            require(data.read() == -1)
            CloudUserRegistry(revision, users, groups, devices, signer, provisions)
        }
    }
}

internal enum class CloudRegistryFailure(val code: String, val explanation: String) {
    ENVELOPE("REG-01", "Неизвестный формат зашифрованного файла. Обновите приложение."),
    AUTHENTICATION("REG-02", "Файл не расшифровывается текущим ключом: ключ изменился или файл повреждён. Используйте исходный телефон либо актуальную копию .blcloud."),
    PAYLOAD("REG-03", "Подлинность файла подтверждена, но формат записей не поддерживается или нарушен. Обновите приложение; не удаляйте файл."),
    KEY_PROVIDER("REG-04", "Android Keystore не смог выполнить расшифровку. Перезапустите телефон. Если ошибка повторится, потребуется восстановление."),
    FILE_HASH("REG-05", "Полученный файл не соответствует версии GitHub. Повторите загрузку."),
}

internal class CloudRegistryReadException(val failure: CloudRegistryFailure, cause: Throwable? = null) :
    Exception(failure.code, cause)

/** Verify the Git blob ID against the exact returned bytes (not an authenticity signature). */
internal object CloudRegistryBlob {
    fun sha(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII))
        return digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun verify(bytes: ByteArray, sha: String) {
        if (sha(bytes) != sha) throw CloudRegistryReadException(CloudRegistryFailure.FILE_HASH)
    }

    /** GitHub wraps Base64 at line boundaries. Reject all other unexpected characters. */
    fun decodeBase64(content: String): ByteArray {
        require(content.length <= (CloudRegistryCodec.MAX_BYTES + 29) * 2)
        val normalized = content.filterNot { it == '\n' || it == '\r' || it == ' ' || it == '\t' }
        return java.util.Base64.getDecoder().decode(normalized).also {
            require(it.size in 29..CloudRegistryCodec.MAX_BYTES + 29)
        }
    }

    fun matches(bytes: ByteArray, sha: String): Boolean =
        bytes.size in 29..CloudRegistryCodec.MAX_BYTES + 29 && sha(bytes) == sha
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
        if (bytes.size !in 29..CloudRegistryCodec.MAX_BYTES + 29 || bytes[0] != 1.toByte())
            throw CloudRegistryReadException(CloudRegistryFailure.ENVELOPE)
        val plain = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            cipher.updateAAD(aad)
            cipher.doFinal(bytes, 13, bytes.size - 13)
        } catch (e: AEADBadTagException) {
            throw CloudRegistryReadException(CloudRegistryFailure.AUTHENTICATION, e)
        } catch (e: BadPaddingException) {
            // Some Android providers report GCM tag failure as BadPaddingException.
            throw CloudRegistryReadException(CloudRegistryFailure.AUTHENTICATION, e)
        } catch (e: Exception) {
            throw CloudRegistryReadException(CloudRegistryFailure.KEY_PROVIDER, e)
        }
        return try { CloudRegistryCodec.decode(plain) }
        catch (e: Exception) { throw CloudRegistryReadException(CloudRegistryFailure.PAYLOAD, e) }
        finally { plain.fill(0) }
    }
}
