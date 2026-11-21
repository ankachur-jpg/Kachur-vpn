package com.kachur.vpn

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.security.MessageDigest

internal class VpnProfileLabels(private val context: Context) {
    private val previous = context.getSharedPreferences("vpn_profile_labels", Context.MODE_PRIVATE)
    private val preferences = SplitTunnelingSettings(context).preferences

    fun country(documentKey: String): String =
        preferences.getString(countryKey(documentKey), null) ?: previous.getString(documentKey, null)
            ?: context.getString(R.string.vpn_country_default)

    fun saveCountry(documentKey: String, input: String): Boolean {
        val country = input.trim()
        if (!validCountry(country)) return false
        synchronized(SplitTunnelingSettings.ipSettingsLock) { putCountry(preferences.edit(), documentKey, country).apply() }
        return true
    }

    companion object {
        const val MAX_LENGTH = 48
        private fun countryKey(documentKey: String) = "vpn_country_" + documentKey

        fun putCountry(writer: SharedPreferences.Editor, documentKey: String, country: String): SharedPreferences.Editor =
            writer.putString(countryKey(documentKey), country)

        fun validCountry(country: String): Boolean = country.length in 1..MAX_LENGTH && country.any(Char::isLetter) &&
            country.all { it.isLetter() || it in " -'’" || Character.getType(it) == Character.NON_SPACING_MARK.toInt() }

        // Keep the label across imports of the same document without saving its URI or profile.
        fun documentKey(uri: Uri): String = MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
