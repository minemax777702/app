package com.mok.buylist.cloud

import java.security.SecureRandom

internal object CloudMembership {
    suspend fun prepare(admin: GitHubCloudAdmin, current: CloudUserRegistry,
                        transform: (CloudUserRegistry) -> CloudUserRegistry): CloudUserRegistry {
        val draft = transform(current.copy(provisions = emptyList()))
        val signer = current.adminSigner ?: return draft
        val updated = current.provisions.map { old ->
            if (old.retired) return@map old
            val grants = try { draft.grantsFor(old.space) } catch (_: IllegalArgumentException) { emptyList() }
            val valid = grants.isNotEmpty() && grants.any { it.role == CloudGroupRole.OWNER }
            if (!valid) return@map old.copy(retired = true)
            val oldManifest = old.signed().manifest
            if (grants == oldManifest.grants.sortedBy { it.deviceId }) return@map old
            require(grants.all { grant -> draft.devices.any { it.fingerprint == grant.deviceId && it.encryptionPublicKey.isNotEmpty() } }) {
                "Перед изменением состава обновите ключи устройств участников."
            }
            val snapshot = admin.snapshotForRotation(old, signer)
            val signed = SignedCloudManifest.sign(CloudManifest(old.space, Math.addExact(oldManifest.epoch, 1L),
                Math.addExact(oldManifest.revision, 1L), grants), signer.signingKey())
            val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val provision = try { CloudProvision(CloudInviteCrypto.encode(signed.encode()), CloudInviteCrypto.encode(raw)) }
                finally { raw.fill(0) }
            val checkpoint = CloudEpochCheckpoint.seal(snapshot, signed.manifest, provision.key())
            provision.copy(checkpointSha = admin.storeCheckpoint(checkpoint))
        }
        return draft.copy(adminSigner = signer, provisions = updated)
    }

    /** Prepare every eligible personal/group list automatically, in the same registry revision. */
    fun complete(current: CloudUserRegistry, revision: Long): CloudUserRegistry {
        var result = current
        val spaces = current.users.filterNot { it.archived }.map { CloudSpace(CloudSpaceKind.PERSONAL, it.id) } +
            current.groups.filterNot { it.archived }.map { CloudSpace(CloudSpaceKind.GROUP, it.id) }
        for (space in spaces) {
            if (result.provisions.any { it.space == space }) continue
            val grants = result.grantsFor(space)
            if (grants.none { it.role == CloudGroupRole.OWNER }) continue
            if (grants.any { grant -> result.devices.none { it.fingerprint == grant.deviceId && it.encryptionPublicKey.isNotEmpty() } }) continue
            result = result.prepareSpace(space)
        }
        return if (result == current) current else result.copy(revision = revision)
    }

    suspend fun publish(admin: GitHubCloudAdmin, registry: CloudUserRegistry, forceDevice: String? = null) {
        val signer = registry.adminSigner ?: return
        for (provision in registry.provisions) {
            admin.publishEpoch(provision, signer)
            if (provision.retired) continue
            val devices = registry.devices.filter { row ->
                !row.revoked && row.encryptionPublicKey.isNotEmpty() &&
                    provision.signed().manifest.grants.any { it.deviceId == row.fingerprint }
            }
            if (devices.isEmpty()) continue
            val checkpoint = admin.invitationCheckpoint(provision)
            for (device in devices) {
                val access = CloudInvitation.create(registry, provision.space, device, checkpoint)
                admin.publishAccess(access, force = device.fingerprint == forceDevice)
            }
        }
        // Never enable deliberately disabled devices. Only reconcile rights of already active keys.
        val active = admin.deviceAccesses(registry.devices)
        for (device in registry.devices.filter { !it.revoked && active[it.fingerprint] == true }) {
            admin.activateDevice(device, registry.canWriteDevice(device.fingerprint))
        }
    }
}
