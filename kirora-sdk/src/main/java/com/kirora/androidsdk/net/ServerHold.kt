package com.kirora.androidsdk.net

import com.kirora.androidsdk.utils.KiroraPreferences

/** Where a sender keeps the end of the server's `Retry-After` (epoch ms, 0 for none) across restarts. */
internal interface ServerHold {
    fun load(): Long
    fun save(until: Long)

    /** A hold an earlier process kept that hasn't ended by [now], or null; one that has ended is cleared. */
    fun restore(now: Long): Long? {
        val until = load()
        if (until > now) return until
        if (until > 0) save(0L)
        return null
    }

    companion object {
        val NONE: ServerHold = object : ServerHold {
            override fun load() = 0L
            override fun save(until: Long) {}
        }

        fun stored(preferences: KiroraPreferences, sender: String): ServerHold = object : ServerHold {
            override fun load() = preferences.getServerHold(sender)
            override fun save(until: Long) = preferences.setServerHold(sender, until)
        }
    }
}
