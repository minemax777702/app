package com.mok.buylist.cloud

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class CloudSshWireTest {
    @Test fun fingerprintMatchesOpenSshBlob() {
        val blob = byteArrayOf(1, 2, 3)
        val expected = "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(blob))
        org.junit.Assert.assertEquals(expected, CloudSshWire.fingerprint("ecdsa-sha2-nistp256 AQID label"))
    }

    @Test fun publicBlobAndSshSignatureAreCanonical() {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val public = pair.public as ECPublicKey
        val blob = CloudSshWire.publicBlob(public)
        fun DataInputStream.field() = ByteArray(readInt()).also { readFully(it) }
        DataInputStream(ByteArrayInputStream(blob)).use {
            assertEquals(CloudSshWire.ALGORITHM, it.field().toString(Charsets.US_ASCII))
            assertEquals("nistp256", it.field().toString(Charsets.US_ASCII))
            val point = it.field()
            assertEquals(65, point.size)
            assertEquals(4, point[0].toInt())
            assertEquals(public.w.affineX, java.math.BigInteger(1, point.copyOfRange(1, 33)))
            assertEquals(public.w.affineY, java.math.BigInteger(1, point.copyOfRange(33, 65)))
            assertEquals(-1, it.read())
        }
        val message = "test SSH signature".toByteArray()
        val signed = CloudSshWire.sign(pair.private, message)
        val rs = DataInputStream(ByteArrayInputStream(signed)).use {
            assertEquals(CloudSshWire.ALGORITHM, it.field().toString(Charsets.US_ASCII))
            val inner = it.field()
            assertEquals(-1, it.read())
            DataInputStream(ByteArrayInputStream(inner)).use { p -> p.field() to p.field() }
        }
        val (r, s) = rs
        val der = byteArrayOf(0x30, (4 + r.size + s.size).toByte(), 2, r.size.toByte()) + r +
            byteArrayOf(2, s.size.toByte()) + s
        assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(public); update(message) }.verify(der))
        assertTrue(runCatching { CloudSshWire.parseSignature(der + byteArrayOf(0)) }.isFailure)
        assertTrue(runCatching { CloudSshWire.parseSignature(byteArrayOf(0x30, 6, 2, 1, -1, 2, 1, 1)) }.isFailure)
    }
}
