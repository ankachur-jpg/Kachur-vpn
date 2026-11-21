package com.kachur.vpn

import android.content.Context
import android.net.Uri
import java.security.MessageDigest

internal class VpnProfileLabels(private val context: Context) {
    private val preferences = context.getSharedPreferences("vpn_profile_labels", Context.MODE_PRIVATE)

    fun country(documentKey: String): String =
        preferences.getString(documentKey, null) ?: context.getString(R.string.vpn_country_default)

    fun saveCountry(documentKey: String, input: String): Boolean {
        val country = input.trim()
        if (country.length !in 1..MAX_LENGTH || country.none(Char::isLetter) || country.any {
            !it.isLetter() && it !in " -'’" && Character.getType(it) != Character.NON_SPACING_MARK.toInt()
        }) return false
        preferences.edit().putString(documentKey, country).apply()
        return true
    }

    companion object {
        const val MAX_LENGTH = 48

        // Keep the label across imports of the same document without saving its URI or profile.
        fun documentKey(uri: Uri): String = MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
