package com.mok.buylist.cloud

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.interfaces.ECPublicKey
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64

/** SSH wire format for a P-256 key. Private key stays in its original provider. */
internal object CloudSshWire {
    const val ALGORITHM = "ecdsa-sha2-nistp256"

    private fun bytes(block: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { out -> DataOutputStream(out).use { block(it) } }.toByteArray()

    private fun DataOutputStream.field(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    private fun coordinate(value: java.math.BigInteger): ByteArray {
        require(value.signum() >= 0 && value.bitLength() <= 256)
        val unsigned = value.toByteArray().let { if (it.size == 33) it.copyOfRange(1, 33) else it }
        return ByteArray(32).also { unsigned.copyInto(it, 32 - unsigned.size) }
    }

    fun publicBlob(key: ECPublicKey): ByteArray {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(key.encoded)
        CloudDeviceProtocol.publicKey(encoded) // Require the exact P-256 curve.
        val point = byteArrayOf(4) + coordinate(key.w.affineX) + coordinate(key.w.affineY)
        return bytes {
            field(ALGORITHM.toByteArray(Charsets.US_ASCII))
            field("nistp256".toByteArray(Charsets.US_ASCII))
            field(point)
        }
    }

    fun openSsh(key: ECPublicKey): String =
        "$ALGORITHM ${Base64.getEncoder().encodeToString(publicBlob(key))} buylist-ssh-test"

    fun sign(key: PrivateKey, data: ByteArray): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(data) }.sign()
        val (r, s) = parseSignature(der)
        val inner = bytes { field(r); field(s) }
        return bytes { field(ALGORITHM.toByteArray(Charsets.US_ASCII)); field(inner) }
    }

    /** P-256 signatures use short DER lengths; reject negative/noncanonical/oversized integers. */
    internal fun parseSignature(der: ByteArray): Pair<ByteArray, ByteArray> {
        require(der.size in 8..72)
        var cursor = 0
        fun take(): Int {
            require(cursor < der.size)
            return der[cursor++].toInt() and 255
        }
        require(take() == 0x30)
        require(take() == der.size - 2)
        fun integer(): ByteArray {
            require(take() == 2)
            val size = take()
            require(size in 1..33 && cursor + size <= der.size)
            val value = der.copyOfRange(cursor, cursor + size)
            cursor += size
            require(value[0] >= 0)
            require(size == 1 || value[0] != 0.toByte() || value[1] < 0)
            val number = java.math.BigInteger(value)
            require(number.signum() > 0 && number.bitLength() <= 256)
            return value
        }
        val r = integer()
        val s = integer()
        require(cursor == der.size)
        return r to s
    }
}
