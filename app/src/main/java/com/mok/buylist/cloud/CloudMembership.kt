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
                "Перед изменением состава обновите QR-коды устройств участников."
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

    suspend fun publish(admin: GitHubCloudAdmin, registry: CloudUserRegistry) {
        val signer = registry.adminSigner ?: return
        for (provision in registry.provisions) admin.publishEpoch(provision, signer)
    }
}
