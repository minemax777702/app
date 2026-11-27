package com.mok.buylist.update

import org.junit.Assert.*
import org.junit.Test

class ReleaseVersionTest {
    @Test fun betaUsesBuildNumberInVersionName() {
        val beta = requireNotNull(ReleaseVersion.parse("beta-v1.2.0-1042", true))
        assertEquals("1.2.0-beta.1042", beta.name)
        assertEquals(UpdateChannel.BETA, beta.channel)
        assertTrue(beta.isNewerThan(InstalledVersion("1.1", 2)))
        assertFalse(beta.isNewerThan(InstalledVersion(beta.name, 1042)))
    }

    @Test fun stableNeverAcceptsPrereleaseOrDowngrade() {
        assertNull(ReleaseVersion.parse("stable-v1.2.0-1042", true))
        assertNull(ReleaseVersion.parse("beta-v1.2.0-1042", false))
        val stable = requireNotNull(ReleaseVersion.parse("stable-v1.2.0-1042", false))
        assertEquals("1.2.0", stable.name)
        assertFalse(stable.isNewerThan(InstalledVersion("1.2.0-beta.1043", 1043)))
    }

    @Test fun invalidTagsAreIgnored() {
        assertNull(ReleaseVersion.parse("v1.2.0", false))
        assertNull(ReleaseVersion.parse("beta-v1.2.0-99999999999", true))
    }
}
