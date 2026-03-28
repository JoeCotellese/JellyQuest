package com.quest.helloworld.streaming

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
)

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
        Log.i(TAG, "Jellyfin disconnected")
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
