package com.github.andreyasadchy.xtra.ui.tv.upstream

import android.content.Context
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Everything the TV interface needs from the original about the account and the network: who is logged in, the
 * Twitch login by device code, the request headers and the shared HTTP client.
 * Part of the only layer of the TV interface that touches the original code (see CAMBIOS-TV.md, section 2).
 */
object TvUpstreamAccount {

    // region Session

    fun userId(context: Context): String? = context.tokenPrefs().getString(C.USER_ID, null)

    fun isLoggedIn(context: Context): Boolean {
        return !TwitchApiHelper.getGQLHeaders(context, true)[C.HEADER_TOKEN].isNullOrBlank() ||
                !TwitchApiHelper.getHelixHeaders(context)[C.HEADER_TOKEN].isNullOrBlank()
    }

    /** Identifies the current account and tokens, to detect a session change. */
    fun sessionKey(context: Context): String {
        return listOf(
            userId(context),
            TwitchApiHelper.getGQLHeaders(context, true)[C.HEADER_TOKEN],
            TwitchApiHelper.getHelixHeaders(context)[C.HEADER_TOKEN],
        ).joinToString("|")
    }

    /** Forgets the account tokens, like the phone app does before logging in again. */
    fun logout(context: Context) {
        TwitchApiHelper.checkedValidation = false
        context.tokenPrefs().edit {
            putString(C.TOKEN, null)
            putString(C.GQL_HEADERS, null)
            putLong(C.INTEGRITY_EXPIRATION, 0)
            putString(C.GQL_TOKEN2, null)
            putString(C.GQL_TOKEN_WEB, null)
            putString(C.USER_ID, null)
            putString(C.USERNAME, null)
        }
    }

    // endregion

    // region Network

    fun networkLibrary(context: Context): String? = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)

    fun enableIntegrity(context: Context): Boolean = context.prefs().getBoolean(C.ENABLE_INTEGRITY, false)

    fun gqlHeaders(context: Context, includeToken: Boolean = false): Map<String, String> {
        return TwitchApiHelper.getGQLHeaders(context, includeToken)
    }

    fun helixHeaders(context: Context): Map<String, String> = TwitchApiHelper.getHelixHeaders(context)

    fun okHttpClient(context: Context): OkHttpClient {
        return (context.applicationContext as XtraApp).xtraModule.okHttpClient.value
    }

    fun json(context: Context): Json = (context.applicationContext as XtraApp).xtraModule.json

    /** A count shown on the screen, such as viewers or followers ("1.2K"). */
    fun formatCount(count: Int): String = TwitchApiHelper.formatCount(count, true)

    // endregion

    // region Login by device code

    class DeviceCode(val deviceCode: String, val userCode: String)

    sealed interface TokenPoll {
        class Granted(val token: String) : TokenPoll

        /** The user has not authorized the code yet. */
        data object Pending : TokenPoll

        /** The code expired or was rejected. */
        data object Rejected : TokenPoll

        /** A network problem: ask again. */
        data object Retry : TokenPoll
    }

    /** The client id used by the original's code login, including the migration its LoginActivity applies when opened. */
    fun loginClientId(context: Context): String? {
        val prefs = context.prefs()
        if (prefs.getString(C.GQL_CLIENT_ID2, OLD_GQL_CLIENT_ID) == OLD_GQL_CLIENT_ID) {
            prefs.edit {
                putString(C.GQL_CLIENT_ID2, NEW_GQL_CLIENT_ID)
                putString(C.GQL_REDIRECT2, NEW_GQL_REDIRECT)
            }
        }
        return prefs.getString(C.GQL_CLIENT_ID2, OLD_GQL_CLIENT_ID)
    }

    /** Asks Twitch for a code to authorize; null when it cannot be obtained. */
    suspend fun requestDeviceCode(context: Context, clientId: String?, scopes: String): DeviceCode? {
        val response = try {
            authRepository(context).getDeviceCode(networkLibrary(context), "client_id=${clientId}&scopes=$scopes")
        } catch (e: Exception) {
            return null
        }
        val deviceCode = response.deviceCode
        val userCode = response.userCode
        return if (deviceCode == null || userCode == null) null else DeviceCode(deviceCode, userCode)
    }

    suspend fun pollToken(context: Context, clientId: String?, deviceCode: String): TokenPoll {
        val response = try {
            authRepository(context).getToken(
                networkLibrary(context),
                "client_id=${clientId}&device_code=${deviceCode}&grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code",
            )
        } catch (e: Exception) {
            return TokenPoll.Retry
        }
        val token = response.token
        if (!token.isNullOrBlank()) return TokenPoll.Granted(token)
        val message = response.message.orEmpty()
        return if (message.contains("pending", ignoreCase = true) || message.contains("slow_down", ignoreCase = true)) {
            TokenPoll.Pending
        } else {
            TokenPoll.Rejected
        }
    }

    /** Checks that the token belongs to the client id and saves the account, like the original's code login. */
    suspend fun completeLogin(context: Context, clientId: String?, token: String): Boolean {
        val validation = try {
            authRepository(context).validate(networkLibrary(context), TwitchApiHelper.addTokenPrefixGQL(token))
        } catch (e: Exception) {
            null
        }
        if (validation == null || validation.clientId.isBlank() || validation.clientId != clientId) {
            return false
        }
        TwitchApiHelper.checkedValidation = true
        context.prefs().edit { putString(C.GQL_CLIENT_ID2, clientId) }
        context.tokenPrefs().edit {
            putString(C.GQL_TOKEN2, token)
            putString(C.USER_ID, validation.userId)
            putString(C.USERNAME, validation.login)
        }
        return true
    }

    private fun authRepository(context: Context) = (context.applicationContext as XtraApp).xtraModule.authRepository

    // same values as LoginActivity.onCreate in the original
    private const val OLD_GQL_CLIENT_ID = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp"
    private const val NEW_GQL_CLIENT_ID = "ue6666qo983tsx6so1t0vnawi233wa"
    private const val NEW_GQL_REDIRECT = "https://www.twitch.tv/settings/connections"

    // endregion
}
