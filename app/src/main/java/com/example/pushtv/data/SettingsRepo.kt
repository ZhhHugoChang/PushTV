package com.example.pushtv.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

val Context.dataStore by preferencesDataStore(name = "software_urls")

data class SoftwareSettings(
    val updateUrl: String = "",
    val isFavorite: Boolean = false
)

object SettingsRepo {
    private const val URL_PREFIX = "url_"
    private const val FAVORITE_PREFIX = "fav_"

    private fun getUrlKey(packageName: String) = stringPreferencesKey("$URL_PREFIX$packageName")
    private fun getFavKey(packageName: String) = booleanPreferencesKey("$FAVORITE_PREFIX$packageName")

    suspend fun saveUrl(context: Context, packageName: String, url: String) {
        context.dataStore.edit { preferences ->
            preferences[getUrlKey(packageName)] = url
        }
    }

    suspend fun setFavorite(context: Context, packageName: String, isFavorite: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[getFavKey(packageName)] = isFavorite
        }
    }

    suspend fun getAllSettings(context: Context): Map<String, SoftwareSettings> {
        val settings = mutableMapOf<String, SoftwareSettings>()
        context.dataStore.data.first().asMap().forEach { (key, value) ->
            when {
                key.name.startsWith(URL_PREFIX) && value is String -> {
                    val packageName = key.name.removePrefix(URL_PREFIX)
                    settings[packageName] = settings[packageName].orEmpty().copy(updateUrl = value)
                }
                key.name.startsWith(FAVORITE_PREFIX) && value is Boolean -> {
                    val packageName = key.name.removePrefix(FAVORITE_PREFIX)
                    settings[packageName] = settings[packageName].orEmpty().copy(isFavorite = value)
                }
            }
        }
        return settings
    }

    private fun SoftwareSettings?.orEmpty() = this ?: SoftwareSettings()
}
