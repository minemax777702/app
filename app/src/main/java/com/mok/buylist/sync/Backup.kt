package com.mok.buylist.sync

/** Содержимое файла резервной копии: чья это копия и все данные. */
class BackupContents(val ownerId: String, val ownerName: String, val snapshot: Snapshot)

/**
 * Файл резервной копии: метка формата, владелец (id и имя) и тот же снимок, что передаётся по QR. Копия не заменяет
 * данные, а сливается с имеющимися (как при обмене): удалённое остаётся удалённым, свежее побеждает.
 */
object BackupCodec {
    private val MAGIC = byteArrayOf('B'.code.toByte(), 'L'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val VERSION = 1

    fun encode(contents: BackupContents): ByteArray {
        val w = ByteWriter()
        w.raw(MAGIC)
        w.byte(VERSION)
        w.string(contents.ownerId)
        w.string(contents.ownerName)
        w.raw(SyncCodec.encode(contents.snapshot))
        return w.toByteArray()
    }

    /** @throws SyncFormatException если это не копия приложения или файл повреждён. */
    fun decode(bytes: ByteArray): BackupContents {
        val r = ByteReader(bytes)
        val magic = r.raw(MAGIC.size)
        if (!magic.contentEquals(MAGIC)) throw SyncFormatException("это не файл резервной копии")
        if (r.byte() != VERSION) throw SyncFormatException("копия сделана другой версией приложения")
        val ownerId = r.string()
        val ownerName = r.string()
        return BackupContents(ownerId, ownerName, SyncCodec.decode(r.rest()))
    }
}
