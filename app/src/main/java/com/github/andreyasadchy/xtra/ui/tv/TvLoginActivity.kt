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
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.tv.upstream.TvUpstreamAccount
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
            val clientId = TvUpstreamAccount.loginClientId(this@TvLoginActivity)
            val code = TvUpstreamAccount.requestDeviceCode(this@TvLoginActivity, clientId, SCOPES)
            if (code == null) {
                status = Status.ERROR
                return@launch
            }
            userCode = code.userCode
            status = Status.WAITING
            val started = System.currentTimeMillis()
            while (isActive) {
                delay(POLL_INTERVAL)
                if (System.currentTimeMillis() - started > CODE_LIFETIME.inWholeMilliseconds) {
                    status = Status.EXPIRED
                    return@launch
                }
                when (val poll = TvUpstreamAccount.pollToken(this@TvLoginActivity, clientId, code.deviceCode)) {
                    is TvUpstreamAccount.TokenPoll.Granted -> {
                        if (TvUpstreamAccount.completeLogin(this@TvLoginActivity, clientId, poll.token)) {
                            setResult(RESULT_OK)
                            finish()
                        } else {
                            status = Status.ERROR
                        }
                        return@launch
                    }
                    // not authorized yet, or a network hiccup: keep waiting
                    TvUpstreamAccount.TokenPoll.Pending, TvUpstreamAccount.TokenPoll.Retry -> {}
                    // "invalid device code": expired or rejected
                    TvUpstreamAccount.TokenPoll.Rejected -> {
                        status = Status.EXPIRED
                        return@launch
                    }
                }
            }
        }
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
        private val POLL_INTERVAL = 5.seconds
        private val CODE_LIFETIME = 30.minutes
    }
}
