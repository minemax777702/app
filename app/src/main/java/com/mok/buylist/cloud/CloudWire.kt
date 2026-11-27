package com.mok.buylist.cloud

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Bounded, versioned binary envelopes. Decoding is NOT authentication. */
internal object CloudWire {
    const val MANIFEST_MAGIC = 0x424D4631
    const val EVENT_MAGIC = 0x42455631

    fun bytes(block: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { out -> DataOutputStream(out).use { block(it) } }.toByteArray()

    fun <T> read(bytes: ByteArray, max: Int, block: DataInputStream.() -> T): T {
        require(bytes.size in 1..max)
        return DataInputStream(ByteArrayInputStream(bytes)).use {
            val result = block(it)
            require(it.read() == -1) { "Unexpected trailing data" }
            result
        }
    }

    fun writeSpace(out: DataOutputStream, space: CloudSpace) {
        out.writeByte(if (space.kind == CloudSpaceKind.PERSONAL) 1 else 2)
        out.writeUTF(space.id)
    }

    fun readSpace(input: DataInputStream): CloudSpace {
        val kind = when (input.readUnsignedByte()) {
            1 -> CloudSpaceKind.PERSONAL
            2 -> CloudSpaceKind.GROUP
            else -> error("Unknown workspace type")
        }
        return CloudSpace(kind, input.readUTF())
    }

    fun writeBlob(out: DataOutputStream, bytes: ByteArray, max: Int) {
        require(bytes.size in 1..max)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    fun readBlob(input: DataInputStream, max: Int): ByteArray {
        val size = input.readInt()
        require(size in 1..max && size <= input.available())
        return ByteArray(size).also { input.readFully(it) }
    }
}
