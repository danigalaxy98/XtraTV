package com.github.andreyasadchy.xtra.ui.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Twitch login for TV with the device code flow: the user authorizes the code on another device and the
 * login finishes by itself. Same requests and saved values as the code login of the upstream LoginActivity.
 */
class TvLoginActivity : ComponentActivity() {

    private enum class Status { LOADING, WAITING, EXPIRED, ERROR }

    private var userCode by mutableStateOf<String?>(null)
    private var status by mutableStateOf(Status.LOADING)
    private var loginJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvCrashLog.install(this)
        setContent {
            XtraTvTheme {
                LoginScreen()
            }
        }
        requestCode()
    }

    /** Asks Twitch for a new code and waits until it is authorized, it expires or something fails. */
    private fun requestCode() {
        loginJob?.cancel()
        userCode = null
        status = Status.LOADING
        loginJob = lifecycleScope.launch {
            val xtraModule = (application as XtraApp).xtraModule
            val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
            val gqlClientId = gqlClientId()
            val code = try {
                xtraModule.authRepository.getDeviceCode(networkLibrary, "client_id=${gqlClientId}&scopes=$SCOPES")
            } catch (e: Exception) {
                null
            }
            val deviceCode = code?.deviceCode
            val newUserCode = code?.userCode
            if (deviceCode == null || newUserCode == null) {
                status = Status.ERROR
                return@launch
            }
            userCode = newUserCode
            status = Status.WAITING
            val started = System.currentTimeMillis()
            while (isActive) {
                delay(POLL_INTERVAL)
                if (System.currentTimeMillis() - started > CODE_LIFETIME.inWholeMilliseconds) {
                    status = Status.EXPIRED
                    return@launch
                }
                val response = try {
                    xtraModule.authRepository.getToken(networkLibrary, "client_id=${gqlClientId}&device_code=${deviceCode}&grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code")
                } catch (e: Exception) {
                    // a network hiccup: keep waiting
                    continue
                }
                val token = response.token
                if (!token.isNullOrBlank()) {
                    if (!finishLogin(networkLibrary, gqlClientId, token)) {
                        status = Status.ERROR
                    }
                    return@launch
                }
                val message = response.message.orEmpty()
                if (!message.contains("pending", ignoreCase = true) && !message.contains("slow_down", ignoreCase = true)) {
                    // "invalid device code": expired or rejected
                    status = Status.EXPIRED
                    return@launch
                }
            }
        }
    }

    private suspend fun finishLogin(networkLibrary: String?, gqlClientId: String?, token: String): Boolean {
        val validation = try {
            (application as XtraApp).xtraModule.authRepository.validate(networkLibrary, TwitchApiHelper.addTokenPrefixGQL(token))
        } catch (e: Exception) {
            null
        }
        if (validation == null || validation.clientId.isBlank() || validation.clientId != gqlClientId) {
            return false
        }
        TwitchApiHelper.checkedValidation = true
        prefs().edit { putString(C.GQL_CLIENT_ID2, gqlClientId) }
        tokenPrefs().edit {
            putString(C.GQL_TOKEN2, token)
            putString(C.USER_ID, validation.userId)
            putString(C.USERNAME, validation.login)
        }
        setResult(RESULT_OK)
        finish()
        return true
    }

    /** The client id used by the upstream code login, including the migration LoginActivity applies when opened. */
    private fun gqlClientId(): String? {
        if (prefs().getString(C.GQL_CLIENT_ID2, OLD_GQL_CLIENT_ID) == OLD_GQL_CLIENT_ID) {
            prefs().edit {
                putString(C.GQL_CLIENT_ID2, NEW_GQL_CLIENT_ID)
                putString(C.GQL_REDIRECT2, NEW_GQL_REDIRECT)
            }
        }
        return prefs().getString(C.GQL_CLIENT_ID2, OLD_GQL_CLIENT_ID)
    }

    @Composable
    private fun LoginScreen() {
        val focus = remember { FocusRequester() }
        LaunchedEffect(status) {
            runCatching { focus.requestFocus() }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(TvColors.Background)
                .padding(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 720.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.log_in), color = TvColors.Text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.tv_login_instructions),
                    color = TvColors.TextSecondary,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(32.dp))
                Text(
                    text = if (status == Status.WAITING) userCode.orEmpty() else "",
                    color = TvColors.Text,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 8.sp,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(
                        when (status) {
                            Status.LOADING -> R.string.loading
                            Status.WAITING -> R.string.tv_login_waiting
                            Status.EXPIRED -> R.string.tv_login_expired
                            Status.ERROR -> R.string.tv_login_error
                        }
                    ),
                    color = if (status == Status.WAITING || status == Status.LOADING) TvColors.TextSecondary else TvColors.Live,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PillButton(
                        text = stringResource(R.string.tv_login_new_code),
                        onClick = { requestCode() },
                        modifier = Modifier.focusRequester(focus),
                    )
                    PillButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = { finish() },
                    )
                }
            }
        }
    }

    companion object {
        private const val SCOPES = "channel_read+chat%3Aread+user_blocks_edit+user_blocks_read+user_follows_edit+user_read"
        // same values as LoginActivity.onCreate
        private const val OLD_GQL_CLIENT_ID = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp"
        private const val NEW_GQL_CLIENT_ID = "ue6666qo983tsx6so1t0vnawi233wa"
        private const val NEW_GQL_REDIRECT = "https://www.twitch.tv/settings/connections"
        private val POLL_INTERVAL = 5.seconds
        private val CODE_LIFETIME = 30.minutes
    }
}
