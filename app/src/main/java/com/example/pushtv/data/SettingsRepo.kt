package com.example.pushtv.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

val Context.dataStore by preferencesDataStore(name = "software_urls")

data class SoftwareSettings(
    val updateUrl: String = "",
    val isFavorite: Boolean = false
)

object SettingsRepo {
    private const val URL_PREFIX = "url_"
    private const val FAVORITE_PREFIX = "fav_"

    private val KEY_WEBDAV_URL = stringPreferencesKey("webdav_server_url")
    private val KEY_WEBDAV_USERNAME = stringPreferencesKey("webdav_username")
    private val KEY_WEBDAV_PASSWORD = stringPreferencesKey("webdav_password")
    private val KEY_WEBDAV_REMOTE_DIR = stringPreferencesKey("webdav_remote_dir")
    private val KEY_GITHUB_TOKEN = stringPreferencesKey("github_token_encrypted")
    private val KEY_GITHUB_PROXY = stringPreferencesKey("github_download_proxy")

    fun observeGitHubProxy(context: Context) = context.dataStore.data.map {
        decodeGitHubProxy(it[KEY_GITHUB_PROXY])
    }

    suspend fun updateGitHubProxy(context: Context, transform: (GitHubProxySettings) -> GitHubProxySettings) {
        context.dataStore.edit { preferences ->
            val settings = transform(decodeGitHubProxy(preferences[KEY_GITHUB_PROXY]))
            preferences[KEY_GITHUB_PROXY] = JSONObject().apply {
                put("selection", settings.selection)
                put("nodes", JSONArray().apply {
                    settings.customNodes.forEach { node ->
                        put(JSONObject().put("id", node.id).put("name", node.name)
                            .put("prefix", node.prefix))
                    }
                })
            }.toString()
        }
    }

    suspend fun selectGitHubProxy(context: Context, id: String) = updateGitHubProxy(context) {
        require(it.hasSelection(id)) { "代理节点不存在" }
        it.copy(selection = id)
    }

    suspend fun saveGitHubProxyNode(
        context: Context, id: String?, name: String, prefix: String
    ) = updateGitHubProxy(context) { settings ->
        val label = name.trim()
        require(label.length in 1..40 && label.none { it.isISOControl() }) { "节点名称须为 1–40 个字符" }
        val normalized = GitHubProxySettings.normalizePrefix(prefix)
        val nodes = settings.customNodes
        val existing = nodes.firstOrNull { it.id == id }
        require(existing?.prefix == normalized || settings.nodes.none { it.prefix == normalized }) { "此代理地址已存在" }
        if (id == null) {
            require(nodes.size < GitHubProxySettings.MAX_CUSTOM_NODES) { "自定义节点已达上限" }
            settings.copy(customNodes = nodes + GitHubProxyNode(UUID.randomUUID().toString(), label, normalized))
        } else {
            require(nodes.any { it.id == id }) { "代理节点不存在" }
            settings.copy(customNodes = nodes.map {
                if (it.id == id) it.copy(name = label, prefix = normalized) else it
            })
        }
    }

    suspend fun deleteGitHubProxyNode(context: Context, id: String) = updateGitHubProxy(context) {
        require(it.customNodes.any { node -> node.id == id }) { "代理节点不存在" }
        it.copy(
            selection = if (it.selection == id) GitHubProxySettings.DIRECT else it.selection,
            customNodes = it.customNodes.filterNot { node -> node.id == id }
        )
    }

    private fun decodeGitHubProxy(encoded: String?): GitHubProxySettings {
        if (encoded == null) return GitHubProxySettings()
        val json = JSONObject(encoded)
        val nodes = json.optJSONArray("nodes") ?: JSONArray()
        val settings = GitHubProxySettings(
            selection = json.optString("selection", GitHubProxySettings.DIRECT),
            customNodes = List(nodes.length()) { index ->
                val node = nodes.getJSONObject(index)
                GitHubProxyNode(node.getString("id"), node.getString("name"), node.getString("prefix"))
            }
        )
        return if (settings.hasSelection(settings.selection)) settings else settings.copy(selection = GitHubProxySettings.DIRECT)
    }

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

    suspend fun getWebDavConfig(context: Context): WebDavConfig {
        val prefs = context.dataStore.data.first()
        return WebDavConfig(
            serverUrl = prefs[KEY_WEBDAV_URL].orEmpty(),
            username = prefs[KEY_WEBDAV_USERNAME].orEmpty(),
            password = prefs[KEY_WEBDAV_PASSWORD].orEmpty(),
            remoteDir = prefs[KEY_WEBDAV_REMOTE_DIR] ?: "/PushTV/Backups/"
        )
    }

    suspend fun saveWebDavConfig(context: Context, config: WebDavConfig) {
        context.dataStore.edit { preferences ->
            preferences[KEY_WEBDAV_URL] = config.serverUrl
            preferences[KEY_WEBDAV_USERNAME] = config.username
            preferences[KEY_WEBDAV_PASSWORD] = config.password
            preferences[KEY_WEBDAV_REMOTE_DIR] = config.remoteDir
        }
    }

    suspend fun getGitHubToken(context: Context): String {
        val encrypted = context.dataStore.data.first()[KEY_GITHUB_TOKEN].orEmpty()
        return SecretCipher.decrypt(encrypted)
    }

    suspend fun saveGitHubToken(context: Context, token: String) {
        val normalized = token.trim()
        context.dataStore.edit { preferences ->
            if (normalized.isEmpty()) {
                preferences.remove(KEY_GITHUB_TOKEN)
            } else {
                preferences[KEY_GITHUB_TOKEN] = SecretCipher.encrypt(normalized)
            }
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
