package com.microsoft.foundrylocal.lab

import android.content.Context

class ModelDownloadRegistry(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isConfirmed(alias: String): Boolean = preferences.getBoolean(key(alias), false)

    fun markConfirmed(alias: String) {
        preferences.edit().putBoolean(key(alias), true).apply()
    }

    fun forget(alias: String) {
        preferences.edit().remove(key(alias)).apply()
    }

    fun activeModelAlias(): String? = preferences.getString(ACTIVE_MODEL_KEY, null)

    fun markActive(alias: String) {
        preferences.edit().putString(ACTIVE_MODEL_KEY, alias.trim()).apply()
    }

    fun clearActive(alias: String? = null) {
        val current = activeModelAlias()
        if (alias == null || current.equals(alias, ignoreCase = true)) {
            preferences.edit().remove(ACTIVE_MODEL_KEY).apply()
        }
    }

    private fun key(alias: String): String = "downloaded_${alias.trim().lowercase()}"

    private companion object {
        const val PREFERENCES_NAME = "foundry_lab_model_downloads"
        const val ACTIVE_MODEL_KEY = "active_model_alias"
    }
}
