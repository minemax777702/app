package com.mok.buylist.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAccessPolicyTest {
    @Test fun requiresVerifiedOwnerIdAndLogin() {
        assertTrue(CloudAccessPolicy.isOwner(223408630L, "minemax777702"))
        assertFalse(CloudAccessPolicy.isOwner(1L, "minemax777702"))
        assertFalse(CloudAccessPolicy.isOwner(223408630L, "other"))
    }

    @Test fun rejectsPublicWrongOwnerAndWrongRepository() {
        val owner = CloudAccessPolicy.OWNER_ID
        val name = CloudAccessPolicy.FULL_NAME
        assertTrue(CloudAccessPolicy.isPrivateStorage(owner, name, true, "private"))
        assertFalse(CloudAccessPolicy.isPrivateStorage(owner, name, false, "public"))
        assertFalse(CloudAccessPolicy.isPrivateStorage(owner, name, true, "internal"))
        assertFalse(CloudAccessPolicy.isPrivateStorage(1L, name, true, "private"))
        assertFalse(CloudAccessPolicy.isPrivateStorage(owner, "minemax777702/app", true, "private"))
    }
}
