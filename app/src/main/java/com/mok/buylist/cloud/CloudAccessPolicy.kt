package com.mok.buylist.cloud

/** The GitHub account ID is public, immutable identity metadata, not a credential. */
internal object CloudAccessPolicy {
    const val OWNER = "minemax777702"
    const val OWNER_ID = 223408630L
    const val REPOSITORY = "app-cloud"
    const val FULL_NAME = "$OWNER/$REPOSITORY"

    fun isOwner(accountId: Long, login: String): Boolean =
        accountId == OWNER_ID && login.equals(OWNER, ignoreCase = true)

    fun isPrivateStorage(ownerId: Long, name: String, privateRepo: Boolean, visibility: String): Boolean =
        ownerId == OWNER_ID && name.equals(FULL_NAME, ignoreCase = true) &&
            privateRepo && visibility == "private"
}
