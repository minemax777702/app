package com.mok.buylist.cloud

import android.content.Context
import com.mok.buylist.data.LentaProduct
import com.mok.buylist.sync.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

internal data class CloudTestResult(val name: String, val passed: Boolean, val detail: String = "")

/** Exercises the real codecs, journal, merge and isolated Room instances; never contacts GitHub. */
internal object CloudIntegrationProbe {
    private fun pair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private fun publicKey(keys: KeyPair) = Base64.getUrlEncoder().withoutPadding().encodeToString(keys.public.encoded)

    suspend fun run(context: Context, onResult: suspend (CloudTestResult) -> Unit) = withContext(Dispatchers.IO) {
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        val key = SecretKeySpec(secret, "AES")
        secret.fill(0)
        val admin = pair()
        val keysA = pair()
        val keysB = pair()
        val viewerKeys = pair()
        CloudTestPeer(context).use { a ->
            CloudTestPeer(context).use { b ->
                a.repository.saveProfile("Тест А")
                b.repository.saveProfile("Тест Б")
                val space = CloudSpace(CloudSpaceKind.GROUP, UUID.randomUUID().toString())
                val grantA = CloudGrant(a.profile.id, publicKey(keysA), CloudGroupRole.OWNER)
                val grantB = CloudGrant(b.profile.id, publicKey(keysB), CloudGroupRole.MEMBER)
                val viewer = CloudGrant(UUID.randomUUID().toString(), publicKey(viewerKeys), CloudGroupRole.VIEWER)
                val signed = SignedCloudManifest.sign(CloudManifest(space, 1, 1, listOf(grantA, grantB, viewer)), admin.private)
                val manifest = SignedCloudManifest.decode(signed.encode())
                val initial = CloudJournalRecord.initialize(space, manifest, publicKey(admin))
                val testRun = UUID.randomUUID()
                val journal = CloudJournalStore(context, testRun)
                var state = initial
                try {
                    journal.initialize(space, manifest, publicKey(admin))

                    suspend fun step(name: String, body: suspend () -> Unit) {
                        coroutineContext.ensureActive()
                        try {
                            body()
                            onResult(CloudTestResult(name, true))
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            onResult(CloudTestResult(name, false, e.message.orEmpty().take(160)))
                            throw IllegalStateException("Диагностика остановлена: $name", e)
                        }
                    }

                    fun event(grant: CloudGrant, keys: KeyPair, delta: Snapshot): CloudSignedEvent {
                        val cursor = state.state.cursors[grant.deviceId] ?: CloudCursor()
                        val header = CloudEventHeader(space, 1, 1, grant.deviceId, cursor.sequence + 1, cursor.digest)
                        return CloudSignedEvent.decode(CloudSignedEvent.seal(header, delta, key, keys.private).encode())
                    }

                    val milk = LentaProduct(70001, "Тестовое молоко", "", "", "", "", "", "")
                    val bread = milk.copy(id = 70002, name = "Тестовый хлеб")
                    lateinit var eventA: CloudSignedEvent
                    lateinit var eventB: CloudSignedEvent

                    step("Раздельные профили и базы Room") {
                        check(a.profile.id != b.profile.id)
                        check(a.repository.addProduct(milk))
                        check(b.repository.addProduct(bread))
                        check(a.repository.getItems().single().productId == milk.id)
                        check(b.repository.getItems().single().productId == bread.id)
                    }
                    step("Подписанные события двух устройств") {
                        eventA = event(grantA, keysA, a.repository.exportSnapshot())
                        eventB = event(grantB, keysB, b.repository.exportSnapshot())
                        state = journal.accept(space, eventA.encode(), manifest.encode(), key)
                        state = journal.accept(space, eventB.encode(), manifest.encode(), key)
                        check(state.state.snapshot.items.map { it.productId }.toSet() == setOf(milk.id, bread.id))
                        val reverse = initial.accept(eventB, manifest, key).accept(eventA, manifest, key)
                        check(reverse.state.snapshot == state.state.snapshot)
                    }
                    step("Объединение без замены базы") {
                        a.repository.applyRemote(state.state.snapshot)
                        b.repository.applyRemote(state.state.snapshot)
                        check(a.repository.getItems().map { it.productId }.toSet() == setOf(milk.id, bread.id))
                        check(b.repository.getItems().map { it.productId }.toSet() == setOf(milk.id, bread.id))
                    }
                    step("Зашифрованный журнал и повтор события") {
                        val reopened = CloudJournalStore(context, testRun)
                        check(reopened.read(space) == state)
                        val before = state
                        check(reopened.accept(space, eventA.encode(), manifest.encode(), key) == before)
                        check(reopened.accept(space, eventB.encode(), manifest.encode(), key) == before)
                    }
                    step("Изменение количества и удаление") {
                        val before = a.repository.exportSnapshot()
                        a.repository.setQuantity(a.repository.getItems().first { it.productId == milk.id }.id, 3)
                        a.repository.remove(a.repository.getItems().first { it.productId == bread.id }.id)
                        val delta = CloudSnapshotDelta.between(before, a.repository.exportSnapshot())
                        check(delta.items.any { it.productId == milk.id && it.quantity == 3 })
                        check(delta.tombs.isNotEmpty())
                        state = journal.accept(space, event(grantA, keysA, delta).encode(), manifest.encode(), key)
                        b.repository.applyRemote(state.state.snapshot)
                        check(b.repository.getItems().single().productId == milk.id)
                        check(b.repository.getItems().single().quantity == 3)
                    }
                    step("Защита от чужой группы и записи читателя") {
                        val other = CloudSpace(CloudSpaceKind.GROUP, UUID.randomUUID().toString())
                        check(runCatching { CloudJournalCodec.decrypt(other, CloudJournalCodec.encrypt(state, key), key) }.isFailure)
                        val viewerEvent = event(viewer, viewerKeys, Snapshot())
                        check(runCatching { state.accept(viewerEvent, manifest, key) }.isFailure)
                        val corrupted = eventA.encode().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                        check(runCatching { initial.accept(CloudSignedEvent.decode(corrupted), manifest, key) }.isFailure)
                    }
                    step("Покупка и сохранение архива") {
                        val before = a.repository.exportSnapshot()
                        a.repository.buy(a.repository.getItems().single().id)
                        a.repository.finishTrip()
                        val after = a.repository.exportSnapshot()
                        check(after.archives.isNotEmpty())
                        val delta = CloudSnapshotDelta.between(before, after)
                        state = journal.accept(space, event(grantA, keysA, delta).encode(), manifest.encode(), key)
                        b.repository.applyRemote(state.state.snapshot)
                        val other = b.repository.exportSnapshot()
                        check(after.archives.map { it.uid }.toSet() == other.archives.map { it.uid }.toSet())
                        check(after.archives.flatMap { it.purchaseEvents }.filter { it.isNotBlank() }.isNotEmpty())
                        check(after.archives.flatMap { it.purchaseEvents }.filter { it.isNotBlank() }.toSet() ==
                            other.archives.flatMap { it.purchaseEvents }.filter { it.isNotBlank() }.toSet())
                    }
                } finally {
                    withContext(NonCancellable) { journal.cleanTest(space) }
                }
            }
        }
    }
}
