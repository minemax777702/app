package com.mok.buylist.cloud

import org.junit.Assert.*
import org.junit.Test

class CloudRecoveryCodecTest {
    @Test fun portableCopyRoundTripsAndAuthenticatesAllData() {
        val registry = CloudUserRegistry().add("Тест")
        val record = CloudRegistryRecovery(registry, "0123456789abcdef0123456789abcdef01234567")
        val password = "unique test recovery phrase".toCharArray()
        val encrypted = CloudRecoveryCodec.encode(record, password)
        assertEquals(record, CloudRecoveryCodec.decode(encrypted, password))
        assertTrue(runCatching { CloudRecoveryCodec.decode(encrypted, "different test recovery phrase".toCharArray()) }.isFailure)
        val corrupt = encrypted.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertTrue(runCatching { CloudRecoveryCodec.decode(corrupt, password) }.isFailure)
        assertTrue(runCatching { CloudRecoveryCodec.encode(record, "short".toCharArray()) }.isFailure)
        password.fill('\u0000')
    }
}
