package com.mok.buylist.nearby

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.mok.buylist.data.ShoppingRepository
import com.mok.buylist.sync.SyncCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Только пока Activity на экране; без фоновой службы. */
class NearbySync(private val context: Context, private val repository: ShoppingRepository) {
    data class UiState(val status: String = "Выключено", val trusted: List<String> = emptyList(), val active: Boolean = false)
    val trust = TrustStore(context)
    private val _state = MutableStateFlow(UiState(trusted = trust.trusted()))
    val state: StateFlow<UiState> = _state
    private val client by lazy { Nearby.getConnectionsClient(context) }
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var active = false
    private var generation = 0
    private var endpoint: String? = null
    private var peer: String? = null
    private var channel: SecureChannel? = null
    private var helloSent: CompletableDeferred<Unit>? = null
    private var incoming: ByteArrayOutputStream? = null
    private var expected = -1
    private var digest: ByteArray? = null
    private var receivedChunks = 0
    private var mailbox = Channel<Pair<Int, ByteArray>>(Channel.UNLIMITED)
    private var receivedSnapshot = false
    private var peerConfirmed = false
    private var localSnapshotSent = false
    private var completed = false
    private val sendMutex = Mutex()
    private val serviceId = "com.mok.buylist.nearby.sync.v1"

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun refreshTrust() { _state.value = _state.value.copy(trusted = trust.trusted()) }
    fun remove(id: String) {
        trust.remove(id); refreshTrust()
        if (peer == id) closeSession("Доверенное устройство удалено")
    }
    fun trustQr(text: String): Boolean {
        val ok = trust.trustQr(text) != null
        refreshTrust()
        // Доверие не запускает поиск автоматически: только по кнопке пользователя.
        return ok
    }

    fun start() {
        if (active) return
        if (trust.trusted().isEmpty()) { status("Сначала обменяйтесь QR доверия"); return }
        if (!hasPermissions()) { status("Разрешите устройства поблизости"); return }
        val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (bluetooth == null || !bluetooth.isEnabled) {
            status("Включите Bluetooth в настройках телефона")
            return
        }
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) {
            status("Google Play Services недоступны. Используйте QR"); return
        }
        active = true
        generation++
        resetSession()
        mailbox = Channel(Channel.UNLIMITED)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sessionGeneration = generation
        scope.launch {
            for ((owner, bytes) in mailbox) {
                if (!active || sessionGeneration != generation || owner != sessionGeneration) continue
                val id = endpoint ?: continue
                try { handle(id, bytes) }
                catch (e: Exception) {
                    status("Ошибка защищённого обмена: ${e.message ?: "повреждены данные"}")
                    client.disconnectFromEndpoint(id)
                }
            }
        }
        _state.value = _state.value.copy(active = true, status = "Поиск доверенного телефона…")
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()
        client.startAdvertising(trust.ownFingerprint, serviceId, lifecycle, options)
            .addOnFailureListener { status("Не удалось начать обнаружение: ${it.localizedMessage}") }
        client.startDiscovery(serviceId, discovery, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build())
            .addOnFailureListener { status("Не удалось начать поиск: ${it.localizedMessage}") }
    }

    fun stop() = closeSession("Поиск остановлен")

    private fun closeSession(message: String) {
        active = false
        generation++
        runCatching { client.stopDiscovery(); client.stopAdvertising(); client.stopAllEndpoints() }
        mailbox.close()
        resetSession()
        scope.cancel()
        _state.value = _state.value.copy(active = false, status = message)
    }

    private fun status(s: String) { _state.value = _state.value.copy(status = s, trusted = trust.trusted()) }
    private fun resetSession() {
        endpoint = null; peer = null; channel = null; helloSent = null
        incoming = null; expected = -1; digest = null; receivedChunks = 0
        receivedSnapshot = false; peerConfirmed = false; localSnapshotSent = false; completed = false
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            if (!active || endpoint != null || info.endpointName !in trust.trusted()) return
            // Детерминированный инициатор: обе стороны рекламируют себя, запрос отправляет только меньший ID.
            if (trust.ownFingerprint < info.endpointName) {
                endpoint = id
                client.requestConnection(trust.ownFingerprint, id, lifecycle)
                    .addOnFailureListener { if (endpoint == id) { resetSession(); status("Ошибка подключения") } }
            }
        }
        override fun onEndpointLost(id: String) = Unit
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            if (!active || info.endpointName !in trust.trusted() ||
                (endpoint != null && endpoint != id)) {
                client.rejectConnection(id); return
            }
            endpoint = id
            // Это только транспортное принятие. До подписанного HELLO любые данные игнорируются.
            client.acceptConnection(id, receiver)
                .addOnFailureListener { status("Не удалось принять соединение") }
        }
        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            if (!active || endpoint != id) return
            if (result.status.statusCode != ConnectionsStatusCodes.STATUS_OK) {
                resetSession(); status("Соединение отклонено"); return
            }
            client.stopDiscovery(); client.stopAdvertising()
            channel = SecureChannel(trust)
            val ready = CompletableDeferred<Unit>()
            helloSent = ready
            status("Проверка доверия…")
            scope.launch {
                try {
                    sendRaw(id, byteArrayOf(1) + requireNotNull(channel).hello())
                    ready.complete(Unit)
                } catch (e: Exception) {
                    ready.completeExceptionally(e)
                    status("Ошибка приветствия: ${e.localizedMessage}")
                }
            }
        }
        override fun onDisconnected(id: String) {
            if (endpoint == id && active) {
                closeSession("Соединение прервано. Повторите обмен вручную")
            }
        }
    }

    private val receiver = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            if (!active || id != endpoint || payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            if (bytes.isEmpty() || bytes.size > 67 * 1024) return
            // Очередь сохраняет порядок пакетов даже при параллельных callbacks Nearby.
            mailbox.trySend(generation to bytes)
        }
        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) = Unit
    }

    private suspend fun handle(id: String, message: ByteArray) {
        when (message[0].toInt()) {
            1 -> {
                require(channel != null && peer == null) { "Повторное приветствие" }
                peer = requireNotNull(channel).acceptHello(message.copyOfRange(1, message.size))
                requireNotNull(helloSent).await()
                status("Защищённое соединение установлено")
                sendSnapshot(id)
            }
            2 -> {
                require(peer != null) { "Нет проверки устройства" }
                val clear = requireNotNull(channel).decrypt(message.copyOfRange(1, message.size))
                when (clear[0].toInt()) {
                    1 -> {
                        require(incoming == null && clear.size == 37) { "Неверное начало" }
                        val size = ByteBuffer.wrap(clear, 1, 4).int
                        require(size in 1..MAX_SNAPSHOT) { "Снимок слишком большой" }
                        expected = size; digest = clear.copyOfRange(5, 37)
                        incoming = ByteArrayOutputStream(size.coerceAtMost(65536))
                        receivedChunks = 0
                    }
                    2 -> {
                        val stream = requireNotNull(incoming) { "Нет начала передачи" }
                        require(clear.size >= 6 && clear.size <= 32 * 1024 + 5) { "Неверный блок" }
                        require(ByteBuffer.wrap(clear, 1, 4).int == receivedChunks++) { "Нарушен порядок блоков" }
                        require(stream.size() + clear.size - 5 <= expected) { "Превышен размер" }
                        stream.write(clear, 5, clear.size - 5)
                        if (stream.size() == expected) {
                            val data = stream.toByteArray()
                            require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(data), digest)) { "Контрольная сумма не совпадает" }
                            incoming = null
                            val snapshot = SyncCodec.decode(data)
                            repository.saveUndoPoint()
                            repository.applyRemote(snapshot)
                            receivedSnapshot = true
                            // Подтверждение отправляем только ПОСЛЕ успешной записи в Room.
                            sendEncrypted(id, byteArrayOf(3))
                            status("Данные получены. Ожидаю подтверждение второго телефона…")
                            finishIfBothConfirmed()
                        }
                    }
                    3 -> {
                        require(!peerConfirmed) { "Повторное подтверждение" }
                        peerConfirmed = true
                        finishIfBothConfirmed()
                    }
                    else -> error("Неизвестная команда")
                }
            }
            else -> error("Неверный тип сообщения")
        }
    }

    private fun sendSnapshot(id: String) {
        val generationAtStart = generation
        scope.launch {
            try {
                val bytes = SyncCodec.encode(repository.exportSnapshot())
                require(bytes.size in 1..MAX_SNAPSHOT) { "Снимок слишком большой" }
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                sendEncrypted(id, byteArrayOf(1) + ByteBuffer.allocate(4).putInt(bytes.size).array() + digest)
                bytes.asList().chunked(32 * 1024).forEachIndexed { index, chunk ->
                    if (generationAtStart != generation || endpoint != id) return@launch
                    sendEncrypted(id, byteArrayOf(2) + ByteBuffer.allocate(4).putInt(index).array() + chunk.toByteArray())
                }
                localSnapshotSent = true
                status("Данные отправлены. Ожидаю завершение обмена…")
                finishIfBothConfirmed()
            } catch (e: Exception) { status("Ошибка отправки: ${e.message}") }
        }
    }

    private fun finishIfBothConfirmed() {
        if (completed || !receivedSnapshot || !peerConfirmed || !localSnapshotSent) return
        completed = true
        status("Синхронизация завершена. Поиск отключён")
        // Не открываем новый сеанс автоматически. Короткая пауза даёт Nearby
        // время доставить последний подтверждающий пакет второму телефону.
        scope.launch {
            kotlinx.coroutines.delay(1500)
            if (active && completed) closeSession("Синхронизация завершена. Поиск отключён")
        }
    }

    private suspend fun sendEncrypted(id: String, clear: ByteArray) {
        sendMutex.withLock {
            val secure = requireNotNull(channel).encrypt(clear)
            sendRaw(id, byteArrayOf(2) + secure)
        }
    }
    private suspend fun sendRaw(id: String, data: ByteArray) {
        suspendCancellableCoroutine<Unit> { continuation ->
            client.sendPayload(id, Payload.fromBytes(data))
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(Unit) }
                .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
        }
    }

    companion object {
        const val MAX_SNAPSHOT = 8 * 1024 * 1024
        fun requiredPermissions(): Array<String> = buildList {
            if (Build.VERSION.SDK_INT in 29..32) {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
        }.toTypedArray()
    }
}
