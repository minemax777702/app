package com.mok.buylist.cloud

import org.junit.Assert.*
import org.junit.Test

class CloudDirectoryTest {
    private val group = CloudGroup("group", "Group", "owner")
    private val shared = CloudList("shared", "Shared", null, group.id, 0)
    private val personal = CloudList("personal", "Personal", "owner", null, 0)
    private fun directory(id: String, admin: Boolean = false, active: Boolean = true) =
        CloudDirectory(
            me = CloudPerson(id, id, admin, active),
            groups = listOf(group),
            lists = listOf(shared, personal),
            members = listOf(CloudMember(group.id, "member"))
        )

    @Test fun ownerCanManageListsAndGroup() {
        val d = directory("owner")
        assertTrue(d.manages(group))
        assertTrue(d.manages(shared))
        assertTrue(d.manages(personal))
        assertTrue(d.participates(group))
    }

    @Test fun memberCanManageSharedButNotPersonalLists() {
        val d = directory("member")
        assertTrue(d.participates(group))
        assertFalse(d.manages(group))
        assertTrue(d.manages(shared))
        assertFalse(d.manages(personal))
    }

    @Test fun administratorDoesNotAutomaticallyOwnLists() {
        val d = directory("admin", admin = true)
        assertTrue(d.manages(group))
        assertFalse(d.manages(shared))
        assertFalse(d.manages(personal))
        assertFalse(d.participates(group))
    }

    @Test fun outsiderAndDisabledUsersCannotManageOrParticipate() {
        for (d in listOf(directory("outsider"), directory("owner", active = false),
            directory("member", active = false), directory("admin", admin = true, active = false),
            CloudDirectory())) {
            assertFalse(d.manages(group))
            assertFalse(d.manages(shared))
            assertFalse(d.manages(personal))
            assertFalse(d.participates(group))
        }
    }
}
