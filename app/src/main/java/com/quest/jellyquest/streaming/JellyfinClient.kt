package com.quest.jellyquest.streaming

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.QuickConnectDto
import java.util.UUID

enum class AuthState {
    DISCONNECTED,
    QUICK_CONNECT_PENDING,
    AUTHENTICATED,
    ERROR,
}

data class JellyfinItem(
    val id: UUID,
    val name: String,
    val type: BaseItemKind,
    val isFolder: Boolean,
) {
    companion object {
        fun fromJson(json: JSONObject) = JellyfinItem(
            id = UUID.fromString(json.getString("id")),
            name = json.getString("name"),
            type = BaseItemKind.valueOf(json.getString("type")),
            isFolder = json.getBoolean("isFolder"),
        )
    }
}

/**
 * Thin wrapper around the Jellyfin SDK for Quick Connect authentication,
 * library browsing, and stream URL construction.
 */
class JellyfinClient(private val context: Context) {

    companion object {
        private const val TAG = "VirtualMonitor"
        private const val PREFS_NAME = "jellyfin_credentials"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_USER_ID = "user_id"

        // Hardcoded for development; will be replaced by local discovery
        const val DEFAULT_SERVER_URL = "http://192.168.1.9:8096"

        private const val QUICK_CONNECT_POLL_MS = 5_000L
        private const val KEY_CACHED_LIBRARIES = "cached_libraries"
        private const val KEY_CACHED_ITEMS = "cached_items"
    }

    private val jellyfin: Jellyfin = createJellyfin {
        clientInfo = ClientInfo("JellyQuest", "1.0")
        this.context = this@JellyfinClient.context
    }

    private var api: ApiClient? = null
    private var userId: UUID? = null
    private var baseUrl: String? = null
    private var accessToken: String? = null

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _authState = MutableStateFlow(AuthState.DISCONNECTED)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _quickConnectCode = MutableStateFlow<String?>(null)
    val quickConnectCode: StateFlow<String?> = _quickConnectCode.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _cachedLibraries = MutableStateFlow<List<JellyfinItem>?>(null)
    val cachedLibraries: StateFlow<List<JellyfinItem>?> = _cachedLibraries.asStateFlow()

    private val _cachedItems = MutableStateFlow<Map<UUID, List<JellyfinItem>>>(emptyMap())
    val cachedItems: StateFlow<Map<UUID, List<JellyfinItem>>> = _cachedItems.asStateFlow()

    init {
        // Restore saved credentials if available
        val savedUrl = prefs.getString(KEY_BASE_URL, null)
        val savedToken = prefs.getString(KEY_ACCESS_TOKEN, null)
        val savedUserId = prefs.getString(KEY_USER_ID, null)
        if (savedUrl != null && savedToken != null && savedUserId != null) {
            baseUrl = savedUrl
            accessToken = savedToken
            userId = UUID.fromString(savedUserId)
            api = jellyfin.createApi(baseUrl = savedUrl, accessToken = savedToken)
            _authState.value = AuthState.AUTHENTICATED
            Log.i(TAG, "Restored Jellyfin session for $savedUrl")

            // Restore cached library data from disk for instant browsing
            restoreCacheFromDisk()
        }
    }

    /**
     * Initiate Quick Connect authentication.
     * Displays a code for the user to enter at their Jellyfin server's Quick Connect page,
     * then polls until authorized.
     */
    suspend fun startQuickConnect(serverUrl: String = DEFAULT_SERVER_URL) {
        _authState.value = AuthState.QUICK_CONNECT_PENDING
        _errorMessage.value = null
        _quickConnectCode.value = null

        try {
            val client = jellyfin.createApi(baseUrl = serverUrl)

            // Check if Quick Connect is enabled
            val enabledResponse = client.quickConnectApi.getQuickConnectEnabled()
            if (!enabledResponse.content) {
                _errorMessage.value = "Quick Connect is not enabled on this server"
                _authState.value = AuthState.ERROR
                return
            }

            // Initiate Quick Connect session
            var qcResult = client.quickConnectApi.initiateQuickConnect().content
            _quickConnectCode.value = qcResult.code
            Log.i(TAG, "Quick Connect code: ${qcResult.code}")

            // Poll until authorized
            while (!qcResult.authenticated) {
                delay(QUICK_CONNECT_POLL_MS)
                qcResult = client.quickConnectApi.getQuickConnectState(
                    secret = qcResult.secret,
                ).content
            }

            // Exchange secret for access token
            val authResult = client.userApi.authenticateWithQuickConnect(
                data = QuickConnectDto(secret = qcResult.secret),
            ).content

            client.update(accessToken = authResult.accessToken)
            api = client
            baseUrl = serverUrl
            accessToken = authResult.accessToken
            userId = authResult.user?.id
            _quickConnectCode.value = null

            // Persist for next launch
            prefs.edit()
                .putString(KEY_BASE_URL, serverUrl)
                .putString(KEY_ACCESS_TOKEN, authResult.accessToken)
                .putString(KEY_USER_ID, authResult.user?.id.toString())
                .apply()

            _authState.value = AuthState.AUTHENTICATED
            Log.i(TAG, "Quick Connect authenticated: ${authResult.user?.name} @ $serverUrl")
            prefetchLibraryContent()
        } catch (e: Exception) {
            Log.e(TAG, "Quick Connect failed", e)
            _errorMessage.value = "${e.javaClass.simpleName}: ${e.message}"
            _authState.value = AuthState.ERROR
        }
    }

    /** Disconnect and clear saved credentials. */
    fun disconnect() {
        api = null
        userId = null
        baseUrl = null
        accessToken = null
        prefs.edit().clear().apply()
        _quickConnectCode.value = null
        _authState.value = AuthState.DISCONNECTED
        _errorMessage.value = null
        _cachedLibraries.value = null
        _cachedItems.value = emptyMap()
        prefs.edit()
            .remove(KEY_CACHED_LIBRARIES)
            .remove(KEY_CACHED_ITEMS)
            .apply()
        Log.i(TAG, "Jellyfin disconnected")
    }

    /**
     * Pre-fetch top-level libraries and their immediate children in the background.
     * Called after authentication so the browse panel can display content instantly.
     */
    suspend fun prefetchLibraryContent() {
        Log.i(TAG, "Prefetching library content...")
        val libraries = getLibraries()
        if (libraries.isEmpty()) {
            Log.w(TAG, "Prefetch: no libraries found")
            return
        }
        _cachedLibraries.value = libraries
        Log.i(TAG, "Prefetch: cached ${libraries.size} libraries")

        val items = mutableMapOf<UUID, List<JellyfinItem>>()
        for (library in libraries) {
            val children = getItems(library.id)
            items[library.id] = children
            Log.i(TAG, "Prefetch: cached ${children.size} items for '${library.name}'")
        }
        _cachedItems.value = items
        saveCacheToDisk(libraries, items)
        Log.i(TAG, "Prefetch complete: ${libraries.size} libraries, ${items.values.sumOf { it.size }} total items")
    }

    /** Fetch top-level library views (Movies, TV Shows, etc.). */
    suspend fun getLibraries(): List<JellyfinItem> {
        val client = api ?: return emptyList()
        return try {
            val response = client.userViewsApi.getUserViews()
            response.content.items?.map { it.toJellyfinItem() } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch libraries", e)
            emptyList()
        }
    }

    /** Fetch items within a parent container (library, series, season, folder). */
    suspend fun getItems(parentId: UUID): List<JellyfinItem> {
        val client = api ?: return emptyList()
        return try {
            val response = client.itemsApi.getItems(
                userId = userId,
                parentId = parentId,
                sortBy = listOf(ItemSortBy.SORT_NAME),
                includeItemTypes = listOf(
                    BaseItemKind.MOVIE,
                    BaseItemKind.SERIES,
                    BaseItemKind.SEASON,
                    BaseItemKind.EPISODE,
                    BaseItemKind.FOLDER,
                    BaseItemKind.COLLECTION_FOLDER,
                    BaseItemKind.BOX_SET,
                ),
            )
            response.content.items?.map { it.toJellyfinItem() } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch items for $parentId", e)
            emptyList()
        }
    }

    /** Construct a direct stream URL for a playable item. */
    fun getStreamUrl(itemId: UUID): String {
        return "${baseUrl}/Videos/$itemId/stream?static=true&api_key=$accessToken"
    }

    /** Construct a poster image URL for an item. */
    fun getImageUrl(itemId: UUID): String? {
        val url = baseUrl ?: return null
        return "${url}/Items/$itemId/Images/Primary?maxWidth=300&quality=80&api_key=$accessToken"
    }

    private fun saveCacheToDisk(
        libraries: List<JellyfinItem>,
        items: Map<UUID, List<JellyfinItem>>,
    ) {
        try {
            val librariesJson = JSONArray().apply {
                libraries.forEach { put(it.toJson()) }
            }
            val itemsJson = JSONObject().apply {
                items.forEach { (parentId, children) ->
                    put(parentId.toString(), JSONArray().apply {
                        children.forEach { put(it.toJson()) }
                    })
                }
            }
            prefs.edit()
                .putString(KEY_CACHED_LIBRARIES, librariesJson.toString())
                .putString(KEY_CACHED_ITEMS, itemsJson.toString())
                .apply()
            Log.i(TAG, "Library cache saved to disk")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save library cache", e)
        }
    }

    private fun restoreCacheFromDisk() {
        try {
            val librariesStr = prefs.getString(KEY_CACHED_LIBRARIES, null)
            val itemsStr = prefs.getString(KEY_CACHED_ITEMS, null)
            if (librariesStr != null) {
                val arr = JSONArray(librariesStr)
                val libraries = (0 until arr.length()).map { JellyfinItem.fromJson(arr.getJSONObject(it)) }
                _cachedLibraries.value = libraries
                Log.i(TAG, "Restored ${libraries.size} libraries from disk cache")
            }
            if (itemsStr != null) {
                val obj = JSONObject(itemsStr)
                val items = mutableMapOf<UUID, List<JellyfinItem>>()
                for (key in obj.keys()) {
                    val arr = obj.getJSONArray(key)
                    items[UUID.fromString(key)] = (0 until arr.length()).map {
                        JellyfinItem.fromJson(arr.getJSONObject(it))
                    }
                }
                _cachedItems.value = items
                Log.i(TAG, "Restored ${items.values.sumOf { it.size }} items from disk cache")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore library cache", e)
        }
    }

    private fun JellyfinItem.toJson() = JSONObject().apply {
        put("id", id.toString())
        put("name", name)
        put("type", type.name)
        put("isFolder", isFolder)
    }

    private fun BaseItemDto.toJellyfinItem() = JellyfinItem(
        id = id,
        name = name ?: "Unknown",
        type = type ?: BaseItemKind.FOLDER,
        isFolder = this.isFolder ?: (type in listOf(
            BaseItemKind.SERIES,
            BaseItemKind.SEASON,
            BaseItemKind.FOLDER,
            BaseItemKind.COLLECTION_FOLDER,
            BaseItemKind.BOX_SET,
            BaseItemKind.USER_VIEW,
        )),
    )
}
