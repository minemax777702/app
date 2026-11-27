package com.mok.buylist.data

import android.content.Context
import com.mok.buylist.sync.MutationClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

internal data class LentaAddressVersion(val address: String, val at: Long, val id: String) : Comparable<LentaAddressVersion> {
    override fun compareTo(other: LentaAddressVersion): Int =
        compareValuesBy(this, other, { it.at }, { it.id })
}

internal data class LentaAddressSelection(val account: String?, val address: String)

/** Persisted account-scoped settings shared by every catalog of that account. */
internal class LentaAddressSettings(context: Context) {
    private val prefs = context.getSharedPreferences("lenta_address_settings", Context.MODE_PRIVATE)
    private val legacy = context.getSharedPreferences("lenta_catalog", Context.MODE_PRIVATE)
    private val flows = mutableMapOf<String?, MutableStateFlow<String>>()
    private val _current = MutableStateFlow(LentaAddressSelection(null, read(null)?.address.orEmpty()))
    val current = _current.asStateFlow()

    private fun prefix(account: String?) = account ?: "guest"

    @Synchronized fun read(account: String?): LentaAddressVersion? {
        val key = prefix(account)
        if (prefs.contains("${key}_address")) return LentaAddressVersion(
            prefs.getString("${key}_address", "").orEmpty(),
            prefs.getLong("${key}_at", 0L), prefs.getString("${key}_id", "").orEmpty())
        if (account == null) {
            val old = legacy.getString("address", "").orEmpty().trim().take(500)
            if (old.isNotEmpty()) return LentaAddressVersion(old, 0L, "")
        }
        return null
    }

    @Synchronized fun addressFor(account: String?): StateFlow<String> =
        flows.getOrPut(account) { MutableStateFlow(read(account)?.address.orEmpty()) }.asStateFlow()

    private fun write(account: String?, value: LentaAddressVersion) {
        val key = prefix(account)
        check(prefs.edit().putString("${key}_address", value.address)
            .putLong("${key}_at",value.at).putString("${key}_id",value.id).commit())
        flows[account]?.value = value.address
        if (_current.value.account == account) _current.value = LentaAddressSelection(account,value.address)
    }

    @Synchronized fun selectAccount(account: String?) {
        if (account != null && !prefs.contains("legacy_owner")) {
            // Transfer the original device preference only once. It is a fallback, never
            // a newer modification that overwrites an existing cloud preference.
            val old = read(null)
            if (read(account) == null && old != null && old.address.isNotBlank())
                write(account,LentaAddressVersion(old.address,0L,UUID.randomUUID().toString()))
            check(prefs.edit().putString("legacy_owner",account).commit())
        }
        _current.value = LentaAddressSelection(account,read(account)?.address.orEmpty())
    }

    @Synchronized fun save(account: String?, raw: String) {
        val value = raw.trim().take(500)
        if (read(account)?.address.orEmpty() == value) return
        write(account,LentaAddressVersion(value,MutationClock.next(),UUID.randomUUID().toString()))
    }

    @Synchronized fun acceptRemote(account: String, remote: LentaAddressVersion) {
        MutationClock.observe(remote.at)
        val local = read(account)
        if (local == null || remote > local) write(account,remote)
    }
}
