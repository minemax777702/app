package com.mok.buylist.nearby

import org.junit.Assert.*
import org.junit.Test
import java.security.*

class SecureChannelTest {
    private class Identity : IdentityKeys {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        override val ownFingerprint: String = MessageDigest.getInstance("SHA-256").digest(pair.public.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        private val peers = mutableMapOf<String, PublicKey>()
        fun trust(other: Identity) { peers[other.ownFingerprint] = other.pair.public }
        override fun publicKey(fingerprint: String) = peers[fingerprint]
        override fun sign(bytes: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private); update(bytes); sign()
        }
    }
    private inline fun rejected(block: () -> Unit) {
        try { block(); fail("Должно быть отклонено") } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        } catch (_: javax.crypto.AEADBadTagException) { }
    }

    @Test fun authenticatedEncryptedRoundTrip() {
        val a = Identity(); val b = Identity(); a.trust(b); b.trust(a)
        val ca = SecureChannel(a); val cb = SecureChannel(b)
        val ha = ca.hello(); val hb = cb.hello()
        assertEquals(b.ownFingerprint, ca.acceptHello(hb))
        assertEquals(a.ownFingerprint, cb.acceptHello(ha))
        val message = "Хлеб и молоко".toByteArray()
        assertArrayEquals(message, cb.decrypt(ca.encrypt(message)))
        assertArrayEquals(message, ca.decrypt(cb.encrypt(message)))
    }

    @Test fun replayAndTamperingAreRejected() {
        val a = Identity(); val b = Identity(); a.trust(b); b.trust(a)
        val ca = SecureChannel(a); val cb = SecureChannel(b)
        val ha = ca.hello(); val hb = cb.hello()
        ca.acceptHello(hb); cb.acceptHello(ha)
        val encrypted = ca.encrypt("привет".toByteArray())
        assertEquals("привет", cb.decrypt(encrypted).toString(Charsets.UTF_8))
        rejected { cb.decrypt(encrypted) }
        val changed = ca.encrypt("секрет".toByteArray()).clone()
        changed[changed.lastIndex] = (changed.last().toInt() xor 1).toByte()
        rejected { cb.decrypt(changed) }
    }

    @Test fun untrustedOrAlteredHelloRejected() {
        val a = Identity(); val b = Identity()
        val ca = SecureChannel(a); val cb = SecureChannel(b)
        rejected { ca.acceptHello(cb.hello()) }
        a.trust(b)
        val hello = cb.hello().clone()
        hello[hello.lastIndex] = (hello.last().toInt() xor 1).toByte()
        rejected { ca.acceptHello(hello) }
    }
}
