package com.mok.buylist

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.mok.buylist.sync.Assembler
import com.mok.buylist.sync.BackupCodec
import com.mok.buylist.sync.BackupContents
import com.mok.buylist.sync.Base45
import com.mok.buylist.sync.FRAME_CHUNK_BYTES
import com.mok.buylist.sync.Frame
import com.mok.buylist.sync.FrameCodec
import com.mok.buylist.sync.QrDecoder
import com.mok.buylist.sync.QrEncoder
import com.mok.buylist.sync.QrMatrix
import com.mok.buylist.sync.Snapshot
import com.mok.buylist.sync.SyncArchive
import com.mok.buylist.sync.SyncCodec
import com.mok.buylist.sync.SyncFormatException
import com.mok.buylist.sync.SyncHidden
import com.mok.buylist.sync.SyncHistory
import com.mok.buylist.sync.SyncItem
import com.mok.buylist.sync.SyncMerge
import com.mok.buylist.sync.SyncReceiver
import com.mok.buylist.sync.SyncSender
import com.mok.buylist.sync.SyncTomb
import com.mok.buylist.sync.SyncUser
import com.mok.buylist.sync.TombKind
import com.mok.buylist.sync.crcOf
import com.mok.buylist.sync.splitMessage
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncProtocolTest {
    private val random = Random(7)

    /** Как у настоящих записей: 128 случайных бит, 32 шестнадцатеричных символа. */
    private fun randomUid() = random.nextBytes(16).joinToString("") { "%02x".format(it) }

    private fun archive(i: Int, items: Int = 3) = SyncArchive(
        uid = randomUid(),
        completedAt = 1_000L + i,
        names = (1..items).map { "Продукт $it" },
        buyers = (1..items).map { if (it % 2 == 0) "" else "11111111-1111-1111-1111-111111111111" },
        tripMillis = 1_800_000,
        visits = 1,
    )

    private fun sampleSnapshot(archives: Int = 3) = Snapshot(
        users = listOf(SyncUser("11111111-1111-1111-1111-111111111111", "Макс", 5)),
        items = listOf(
            SyncItem("Молоко 3,2%", 100, 200, false, "11111111-1111-1111-1111-111111111111"),
            SyncItem("Хлеб", 90, 210, true, ""),
        ),
        history = listOf(SyncHistory("Сыр", 4, 300)),
        hidden = listOf(SyncHidden("колбаса", 500)),
        archives = (0 until archives).map { archive(it) },
        tombs = listOf(SyncTomb(TombKind.ITEM, "чай", 600), SyncTomb(TombKind.ARCHIVE, "abc", 700)),
    )

    // --- Base45 и кадры ---

    @Test
    fun base45RoundTripsAnyLength() {
        for (length in 0..40) {
            val bytes = random.nextBytes(length)
            assertArrayEquals(bytes, Base45.decode(Base45.encode(bytes)))
        }
    }

    @Test
    fun base45RejectsForeignText() {
        assertNull(Base45.decode("hello world"))
        assertNull(Base45.decode("A"))
        assertNull(Base45.decode("GGW")) // 16 * 45² = больше 0xFFFF
    }

    @Test
    fun snapshotRoundTrips() {
        val snapshot = sampleSnapshot()
        assertEquals(snapshot, SyncCodec.decode(SyncCodec.encode(snapshot)))
    }

    @Test fun remainingSnapshotSurvivesSyncAndMerge() {
        val record = archive(1).copy(remainingEvents = "v1|encoded-snapshot")
        val snapshot = Snapshot(archives = listOf(record))
        assertEquals(snapshot, SyncCodec.decode(SyncCodec.encode(snapshot)))
        assertEquals(snapshot, BackupCodec.decode(BackupCodec.encode(BackupContents("u", "Пользователь", snapshot))).snapshot)
        assertEquals(record.remainingEvents, SyncMerge.merge(snapshot, snapshot).archives.single().remainingEvents)
    }

    @Test
    fun quantitiesSurviveExchangeAndArchiveDeduplication() {
        val item = SyncItem("Молоко", 1, 2, false, "u", quantity = 3)
        val archive = SyncArchive("id", 3, listOf("Молоко", "Хлеб"), listOf("u", "v"), 0, 1, listOf(4, 2))
        val snapshot = Snapshot(items = listOf(item), archives = listOf(archive))
        assertEquals(snapshot, SyncCodec.decode(SyncCodec.encode(snapshot)))
        assertEquals(3, SyncMerge.merge(Snapshot(), snapshot).items.single().quantity)
    }

    @Test
    fun corruptMessageIsRejectedNotCrashing() {
        val bytes = SyncCodec.encode(sampleSnapshot())
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        try {
            SyncCodec.decode(bytes)
            fail("повреждённое сообщение прочитано")
        } catch (expected: SyncFormatException) {
            // нужный исход
        }
    }

    @Test
    fun frameRoundTripsAndRejectsForeignQr() {
        val frame = Frame(session = -123456, part = 1, total = 300, index = 299, crc = -1, chunk = random.nextBytes(77))
        val back = FrameCodec.decode(FrameCodec.encode(frame))!!
        assertEquals(frame.session, back.session)
        assertEquals(frame.part, back.part)
        assertEquals(frame.total, back.total)
        assertEquals(frame.index, back.index)
        assertEquals(frame.crc, back.crc)
        assertArrayEquals(frame.chunk, back.chunk)
        assertNull(FrameCodec.decode("HTTPS://EXAMPLE.COM"))
    }

    @Test
    fun assemblerAcceptsAnyOrderAndDuplicates() {
        val message = random.nextBytes(1_000)
        val chunks = splitMessage(message, 100)
        val crc = crcOf(message)
        val frames = chunks.mapIndexed { i, c -> Frame(1, 0, chunks.size, i, crc, c) }
        val assembler = Assembler()
        var result: ByteArray? = null
        for (frame in (frames + frames.take(3)).shuffled(random)) {
            assembler.add(frame)?.let { result = it }
        }
        assertArrayEquals(message, result)
        assertTrue(assembler.isComplete)
    }

    @Test
    fun assemblerRestartsOnBadChecksum() {
        val message = random.nextBytes(300)
        val chunks = splitMessage(message, 100)
        val crc = crcOf(message)
        val bad = Frame(1, 0, 3, 1, crc, ByteArray(100)) // другой кусок вместо второго
        val assembler = Assembler()
        assertNull(assembler.add(Frame(1, 0, 3, 0, crc, chunks[0])))
        assertNull(assembler.add(bad))
        assertNull(assembler.add(Frame(1, 0, 3, 2, crc, chunks[2])))
        assertFalse(assembler.isComplete)
        // Со следующего круга приходят верные кадры — сообщение собирается.
        assertNull(assembler.add(Frame(1, 0, 3, 0, crc, chunks[0])))
        assertNull(assembler.add(Frame(1, 0, 3, 2, crc, chunks[2])))
        assertArrayEquals(message, assembler.add(Frame(1, 0, 3, 1, crc, chunks[1])))
    }

    // --- QR ---

    private fun render(matrix: QrMatrix, scale: Int = 6, quiet: Int = 4): RGBLuminanceSource {
        val side = (matrix.size + 2 * quiet) * scale
        val pixels = IntArray(side * side) { 0xFFFFFFFF.toInt() }
        for (y in 0 until matrix.size) for (x in 0 until matrix.size) {
            if (!matrix[x, y]) continue
            for (dy in 0 until scale) for (dx in 0 until scale) {
                pixels[((y + quiet) * scale + dy) * side + (x + quiet) * scale + dx] = 0xFF000000.toInt()
            }
        }
        return RGBLuminanceSource(side, side, pixels)
    }

    @Test
    fun fullFrameFitsVersion12AndSurvivesQrRoundTrip() {
        val frame = Frame(1234, 0, 50, 7, 0x12345678, random.nextBytes(FRAME_CHUNK_BYTES))
        val text = FrameCodec.encode(frame)
        val matrix = QrEncoder.encode(text)
        // Версия 12 = 65 модулей; больше — код станет мельче, и камере труднее.
        assertTrue("QR слишком плотный: ${matrix.size}", matrix.size <= 65)
        val decoded = QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(render(matrix))),
            mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)),
        )
        assertEquals(text, decoded.text)
    }

    /**
     * Кадр камеры: серый фон с шумом, в нём QR по [px] точек на модуль; строки с отступом [rowStride], как у плоскости Y.
     */
    private fun cameraFrame(matrix: QrMatrix, px: Int, width: Int, height: Int, rowStride: Int, transpose: Boolean): ByteArray {
        val luma = ByteArray(rowStride * height)
        for (y in 0 until height) for (x in 0 until width) luma[y * rowStride + x] = (150 + random.nextInt(-12, 13)).toByte()
        val quiet = 4
        val side = (matrix.size + 2 * quiet) * px
        val left = (width - side) / 3
        val top = (height - side) / 2
        for (y in 0 until side) for (x in 0 until side) {
            val mx = x / px - quiet
            val my = y / px - quiet
            val dark = mx in 0 until matrix.size && my in 0 until matrix.size &&
                (if (transpose) matrix[my, mx] else matrix[mx, my])
            luma[(top + y) * rowStride + left + x] = (if (dark) 25 else 235).toByte()
        }
        return luma
    }

    @Test
    fun decoderReadsFramesLikeTheCameraProducesThem() {
        val frame = Frame(77, 0, 9, 3, 0x0badf00d, random.nextBytes(FRAME_CHUNK_BYTES))
        val text = FrameCodec.encode(frame)
        val matrix = QrEncoder.encode(text)
        val decoder = QrDecoder()
        // 1280×720, строка 1344 байта; код крупно, мельче и повёрнутый на 90°.
        for ((px, transpose) in listOf(7 to false, 5 to false, 5 to true)) {
            val luma = cameraFrame(matrix, px, 1280, 720, 1344, transpose)
            assertEquals("px=$px transpose=$transpose", text, decoder.decode(luma, 1344, 1280, 720))
        }
        // Пустой кадр: читаемого кода нет — не исключение, а null.
        val empty = ByteArray(1344 * 720) { 128.toByte() }
        assertNull(decoder.decode(empty, 1344, 1280, 720))
    }

    // --- Показ и приём ---

    /** Всё, что прислал второй телефон: список и архив вместе. */
    private fun whole(receiver: SyncReceiver): Snapshot =
        receiver.list!!.copy(archives = receiver.archive!!.archives)

    /** Показывает кадры по кругу; камера теряет каждый с вероятностью [loss]. Читает со случайного места. */
    private fun transfer(sender: SyncSender, receiver: SyncReceiver, loss: Double, maxFrames: Int = 5_000): Int {
        repeat(random.nextInt(sender.frameCount)) { sender.nextFrame() } // получатель включил камеру не с первого кадра
        var shown = 0
        while (shown < maxFrames) {
            shown++
            val text = sender.nextFrame()
            if (random.nextDouble() >= loss) receiver.onScanned(text)
            if (receiver.progress().done) return shown
        }
        fail("данные не получены за $maxFrames кадров")
        return shown
    }

    @Test
    fun receiverGetsDataDespiteLostFramesAndLateStart() {
        val snapshot = sampleSnapshot(archives = 40)
        val sender = SyncSender(snapshot)
        val receiver = SyncReceiver()
        transfer(sender, receiver, loss = 0.35)
        assertEquals(snapshot, whole(receiver))
        assertTrue(receiver.progress().done)
    }

    @Test
    fun foreignQrIsIgnored() {
        val receiver = SyncReceiver()
        assertFalse(receiver.onScanned("HTTPS://EXAMPLE.COM/PAY"))
        assertFalse(receiver.progress().started)
    }

    @Test
    fun restartedSenderStartsFreshTransfer() {
        val receiver = SyncReceiver()
        val first = SyncSender(sampleSnapshot(archives = 30))
        repeat(3) { receiver.onScanned(first.nextFrame()) }
        assertTrue(receiver.progress().started)
        assertFalse(receiver.progress().done)
        // Отправитель закрыл экран и открыл заново: новый сеанс не должен смешаться со старым.
        val second = SyncSender(sampleSnapshot(archives = 1).copy(items = emptyList()))
        transfer(second, receiver, loss = 0.0)
        assertTrue(whole(receiver).items.isEmpty())
    }

    @Test
    fun secondPassSkipsArchivesThePeerJustSent() {
        // Первый телефон показал свой архив (30 записей), второй принял его и дальше показывает свой.
        val firstPhone = sampleSnapshot(archives = 30)
        val secondPhone = firstPhone.copy(archives = firstPhone.archives + (0 until 2).map { archive(100 + it) })

        val receiver = SyncReceiver()
        transfer(SyncSender(firstPhone), receiver, loss = 0.0)
        val known = SyncMerge.helloOf(whole(receiver))

        val full = SyncSender(secondPhone)
        val delta = SyncSender(SyncMerge.forPeer(secondPhone, known))
        assertTrue("второй проход не короче полного: ${delta.frameCount} / ${full.frameCount}", delta.frameCount < full.frameCount)

        val back = SyncReceiver()
        transfer(delta, back, loss = 0.0)
        assertEquals(2, whole(back).archives.size)
        // После слияния у первого телефона то же состояние, что у второго.
        val onFirst = SyncMerge.merge(firstPhone, whole(back))
        val onSecond = SyncMerge.merge(secondPhone, whole(receiver))
        assertEquals(onFirst.archives.map { it.uid }, onSecond.archives.map { it.uid })
        assertEquals(onFirst.items, onSecond.items)
        assertEquals(onFirst.tombs, onSecond.tombs)
    }

    @Test
    fun largeArchiveStaysWithinReasonableFrameCount() {
        // Сто пятьдесят списков по двадцать товаров — около года покупок. Для обмена это должно быть терпимо.
        val names = (1..400).map { "Продукт номер $it" }
        val archives = (0 until 150).map { i ->
            val picked = (0 until 20).map { names[random.nextInt(names.size)] }
            SyncArchive(
                uid = randomUid(),
                completedAt = 1_700_000_000_000L + i * 86_400_000L,
                names = picked,
                buyers = picked.map { "11111111-1111-1111-1111-111111111111" },
                tripMillis = 2_000_000,
                visits = 1,
            )
        }
        val bytes = SyncCodec.encode(Snapshot(archives = archives))
        val frames = splitMessage(bytes).size
        println("год покупок: ${bytes.size} байт, $frames кадров")
        assertTrue("слишком много кадров: $frames", frames < 600)
    }
}
