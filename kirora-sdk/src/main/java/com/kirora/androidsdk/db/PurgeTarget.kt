package com.kirora.androidsdk.db

/** A table of behavioural rows that a refused deletion can be owed against (see `PendingPurges`). */
internal interface PurgeTarget {
    suspend fun purgeAll()

    /** Returns how many rows were deleted. */
    suspend fun purgeOwner(owner: String): Int

    companion object {
        /** As an owner: the rows waiting from before sign-in. Never a token's `sub` (it can't hold NUL). */
        const val UNOWNED = "\u0000unowned"

        /** As an owner: those of [UNOWNED] that continued a signed-in user's visit. */
        const val UNOWNED_ON_SIGNED_IN_VISITS = "\u0000unowned-signed-in-visits"
    }
}
