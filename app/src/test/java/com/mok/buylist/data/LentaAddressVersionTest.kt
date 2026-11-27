package com.mok.buylist.data

import org.junit.Assert.assertTrue
import org.junit.Test

class LentaAddressVersionTest {
    @Test fun newerAddressWinsEvenWhenCleared() {
        val old = LentaAddressVersion("First",100,"z")
        val cleared = LentaAddressVersion("",101,"a")
        assertTrue(cleared > old)
    }
    @Test fun equalTimestampsUseMutationId() {
        assertTrue(LentaAddressVersion("B",100,"b") > LentaAddressVersion("A",100,"a"))
    }
    @Test fun migratedAddressNeverOverwritesExistingCloudChoice() {
        assertTrue(LentaAddressVersion("",100,"a") > LentaAddressVersion("Legacy",0,"z"))
    }
}
