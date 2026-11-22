package com.mok.buylist.sync

import kotlin.random.Random

/** Сколько кадров архива показывается между повторами кадров списка: список должен приходить за секунды. */
private const val ARCHIVE_BLOCK = 12

/**
 * Показ данных: сообщение режется на кадры, которые отдаются по кругу, пока второй телефон не прочитает все.
 * Отправитель не знает, что получатель закончил (ему нечем ответить), поэтому показ идёт, пока его не остановят.
 * Кадры можно начинать читать с любого места — порядок не важен.
 *
 * Данные идут двумя сообщениями: маленькое «список» (товары, пользователи, удаления) и «архив» (основной объём).
 * Кадры списка повторяются между блоками архива, поэтому список получается за пару секунд, а архив догружается.
 *
 * [snapshot] уже отфильтрован под получателя (см. [SyncMerge.forPeer]).
 */
class SyncSender(
    snapshot: Snapshot,
    private val sessionId: Int = Random.nextInt(),
    chunkBytes: Int = FRAME_CHUNK_BYTES,
) {
    private val listMessage = SyncCodec.encode(snapshot.copy(archives = emptyList()))
    private val archiveMessage = SyncCodec.encode(Snapshot(archives = snapshot.archives))
    private val listChunks = splitMessage(listMessage, chunkBytes)
    private val archiveChunks = splitMessage(archiveMessage, chunkBytes)
    private val listCrc = crcOf(listMessage)
    private val archiveCrc = crcOf(archiveMessage)

    /** Порядок показа: все кадры списка, блок кадров архива, снова список, следующий блок… */
    private val playlist: List<Pair<Int, Int>> = buildList {
        for (start in archiveChunks.indices step ARCHIVE_BLOCK) {
            listChunks.indices.mapTo(this) { PART_LIST to it }
            for (i in start until minOf(start + ARCHIVE_BLOCK, archiveChunks.size)) add(PART_ARCHIVE to i)
        }
    }
    private var cursor = 0

    /** Сколько разных кадров в одном круге. */
    val frameCount: Int get() = listChunks.size + archiveChunks.size

    /** Текст следующего QR-кода; вызывать по таймеру. */
    @Synchronized
    fun nextFrame(): String {
        val (part, index) = playlist[cursor++ % playlist.size]
        val frame = if (part == PART_LIST) {
            Frame(sessionId, part, listChunks.size, index, listCrc, listChunks[index])
        } else {
            Frame(sessionId, part, archiveChunks.size, index, archiveCrc, archiveChunks[index])
        }
        return FrameCodec.encode(frame)
    }
}

/** Сколько кадров одного сообщения собрано; [total] неизвестно (0), пока не прочитан первый его кадр. */
data class PartProgress(val received: Int, val total: Int, val done: Boolean)

/** Ход приёма: список приходит первым и применяется сразу, архив догружается. */
data class ReceiveProgress(val started: Boolean, val list: PartProgress, val archive: PartProgress) {
    val done: Boolean get() = list.done && archive.done

    companion object {
        val NONE = ReceiveProgress(false, PartProgress(0, 0, false), PartProgress(0, 0, false))
    }
}

/**
 * Приём данных: собирает кадры, пришедшие из камеры. Если отправитель начал показ заново (другой номер сеанса),
 * прежнее состояние сбрасывается. Методы синхронизированы: камера и экран работают в разных потоках.
 */
class SyncReceiver {
    private var session: Int? = null
    private val listAssembler = Assembler()
    private val archiveAssembler = Assembler()

    /** Список второго телефона, как только он получен целиком (приходит раньше архива). */
    var list: Snapshot? = null
        @Synchronized get
        private set

    /** Архив второго телефона (в [Snapshot.archives]); приходит целиком, когда собраны все его кадры. */
    var archive: Snapshot? = null
        @Synchronized get
        private set

    @Synchronized
    fun progress(): ReceiveProgress = ReceiveProgress(
        started = session != null,
        list = PartProgress(listAssembler.received, listAssembler.expected, list != null),
        archive = PartProgress(archiveAssembler.received, archiveAssembler.expected, archive != null),
    )

    /** Принимает текст прочитанного QR. @return true, если это кадр нашего протокола. */
    @Synchronized
    fun onScanned(text: String): Boolean {
        val frame = FrameCodec.decode(text) ?: return false
        if (session != frame.session) {
            session = frame.session
            listAssembler.reset()
            archiveAssembler.reset()
            list = null
            archive = null
        }
        val assembler = if (frame.part == PART_LIST) listAssembler else archiveAssembler
        assembler.add(frame)?.let { bytes ->
            try {
                val snapshot = SyncCodec.decode(bytes)
                if (frame.part == PART_LIST) list = snapshot else archive = snapshot
            } catch (e: SyncFormatException) {
                // Сообщение целое по контрольной сумме, но не разобралось (другая версия приложения): ждём новый показ.
                assembler.reset()
            }
        }
        return true
    }
}
