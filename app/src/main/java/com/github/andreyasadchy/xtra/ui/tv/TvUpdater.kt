package com.github.andreyasadchy.xtra.ui.tv

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.tv.upstream.TvUpstreamAccount
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import java.io.File
import java.net.URI
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request

/**
 * Update checks for the TV build. It uses the same GitHub "latest" release as the phone app, but:
 * - an APK only counts as new if it was uploaded after the installed version was installed
 *   (the phone screen that saves the first "last checked" date never runs on TV),
 * - the installer reports back to [TvInstallReceiver] instead of the phone screen.
 */
object TvUpdater {

    const val ACTION_INSTALL_STATUS = "com.github.andreyasadchy.xtra.TV_INSTALL_STATUS"
    private const val LAST_AUTO_CHECK = "tv_update_last_auto_check"
    private const val PENDING_SESSION_ID = "tv_update_session_id"
    private const val APK_FILE_NAME = "update.apk"

    @Volatile
    private var cachedSessionId = -1

    class UpdateInfo(
        val downloadUrl: String,
        val size: Long?,
        val publishedAt: Long,
        val notes: String?,
    )

    sealed interface CheckResult {
        object UpToDate : CheckResult
        class Available(val info: UpdateInfo) : CheckResult
        object Failed : CheckResult
    }

    fun installedAt(context: Context): Long {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (e: Exception) {
            0L
        }
    }

    fun versionSummary(context: Context): String {
        val installed = installedAt(context)
        val date = if (installed > 0) formatDateTime(context, installed) else "?"
        return context.getString(R.string.tv_update_version_summary, BuildConfig.VERSION_NAME, date)
    }

    fun formatDateTime(context: Context, millis: Long): String {
        val date = Date(millis)
        return "${DateFormat.getDateFormat(context).format(date)} ${DateFormat.getTimeFormat(context).format(date)}"
    }

    /** True when automatic checks are enabled and the chosen number of days has passed since the last one. */
    fun shouldAutoCheck(context: Context): Boolean {
        if (!context.prefs().getBoolean(C.UPDATE_CHECK_ENABLED, false)) return false
        val days = context.prefs().getString(C.UPDATE_CHECK_FREQUENCY, "7")?.toLongOrNull() ?: 7L
        val last = context.tokenPrefs().getLong(LAST_AUTO_CHECK, 0)
        return System.currentTimeMillis() - last > days * 86_400_000L
    }

    suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        val url = context.prefs().getString(C.UPDATE_URL, null)?.takeIf { it.isNotBlank() && isAllowedReleaseUrl(it) }
            ?: return@withContext CheckResult.Failed
        try {
            val body = TvUpstreamAccount.okHttpClient(context).newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext CheckResult.Failed
                response.body.string()
            }
            context.tokenPrefs().edit { putLong(LAST_AUTO_CHECK, System.currentTimeMillis()) }
            val release = TvUpstreamAccount.json(context).decodeFromString<JsonObject>(body)
            val asset = release["assets"]?.jsonArray?.map { it.jsonObject }?.find {
                it["content_type"]?.jsonPrimitive?.contentOrNull == "application/vnd.android.package-archive" ||
                        it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true
            } ?: return@withContext CheckResult.UpToDate
            val uploadedAt = asset["updated_at"]?.jsonPrimitive?.contentOrNull
                ?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() } ?: return@withContext CheckResult.Failed
            val downloadUrl = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { isAllowedDownloadUrl(it) } ?: return@withContext CheckResult.Failed
            // "not now" saves the dismissed build's upload time in UPDATE_LAST_CHECKED so only newer builds are offered
            val baseline = maxOf(installedAt(context), context.tokenPrefs().getLong(C.UPDATE_LAST_CHECKED, 0))
            if (uploadedAt > baseline) {
                CheckResult.Available(
                    UpdateInfo(
                        downloadUrl = downloadUrl,
                        size = asset["size"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 },
                        publishedAt = uploadedAt,
                        notes = release["body"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
                    )
                )
            } else {
                CheckResult.UpToDate
            }
        } catch (e: Exception) {
            CheckResult.Failed
        }
    }

    private fun isAllowedReleaseUrl(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return false
        }
        return uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals("api.github.com", ignoreCase = true) &&
                (uri.port == -1 || uri.port == 443) &&
                uri.rawPath?.startsWith("/repos/") == true
    }

    private fun isAllowedDownloadUrl(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return false
        }
        return uri.scheme.equals("https", ignoreCase = true) &&
                (uri.port == -1 || uri.port == 443) &&
                isAllowedDownloadHost(uri.host)
    }

    private fun isAllowedDownloadHost(host: String?): Boolean {
        val lower = host?.lowercase() ?: return false
        return lower == "github.com" || lower.endsWith(".githubusercontent.com")
    }

    private fun apkFile(context: Context) = File(context.cacheDir, APK_FILE_NAME)

    /**
     * Downloads the APK to the cache dir reporting progress from 0 to 1 (or -1 when the size is unknown).
     * Returns null on failure; the partial file is deleted on failure or cancellation.
     */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File? = withContext(Dispatchers.IO) {
        val file = apkFile(context)
        file.delete()
        try {
            val complete = TvUpstreamAccount.okHttpClient(context).newCall(Request.Builder().url(info.downloadUrl).build()).execute().use { response ->
                // redirects go to GitHub's asset storage, check where we ended up
                val finalUrl = response.request.url
                if (!response.isSuccessful || !finalUrl.isHttps || !isAllowedDownloadHost(finalUrl.host)) return@use false
                val contentLength = response.body.contentLength().takeIf { it > 0 }
                if (info.size != null && contentLength != null && contentLength != info.size) return@use false
                val total = info.size ?: contentLength
                val buffer = ByteArray(64 * 1024)
                var read = 0L
                var lastReported = -1
                response.body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            read += count
                            if (total != null && read > total) break
                            output.write(buffer, 0, count)
                            val percent = if (total != null) (read * 100 / total).toInt() else -1
                            if (percent != lastReported) {
                                lastReported = percent
                                withContext(Dispatchers.Main) { onProgress(if (total != null) read.toFloat() / total else -1f) }
                            }
                        }
                    }
                }
                read > 0 && (total == null || read == total)
            }
            if (complete) {
                file
            } else {
                file.delete()
                null
            }
        } catch (e: CancellationException) {
            file.delete()
            throw e
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    /** Streams the downloaded APK into an installer session and deletes it; the result goes to [TvInstallReceiver]. */
    fun install(context: Context, apk: File): Boolean {
        val installer = context.packageManager.packageInstaller
        var sessionId = -1
        return try {
            abandonPreviousSession(context)
            sessionId = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
            setPendingSessionId(context, sessionId)
            installer.openSession(sessionId).use { session ->
                session.openWrite("package", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output, 64 * 1024) }
                    session.fsync(output)
                }
                session.commit(
                    PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent(context, TvInstallReceiver::class.java).setAction(ACTION_INSTALL_STATUS),
                        // the installer has to fill in the status extras
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ).intentSender
                )
            }
            true
        } catch (e: Exception) {
            if (sessionId != -1) {
                try {
                    installer.abandonSession(sessionId)
                } catch (e: Exception) {
                }
                clearPendingSessionId(context, sessionId)
            }
            false
        } finally {
            apk.delete()
        }
    }

    private fun abandonPreviousSession(context: Context) {
        val previous = pendingSessionId(context)
        if (previous == -1) return
        try {
            context.packageManager.packageInstaller.abandonSession(previous)
        } catch (e: Exception) {
        }
        clearPendingSessionId(context, previous)
    }

    /** Id of the last installer session created by [install], or -1. */
    fun pendingSessionId(context: Context): Int {
        return cachedSessionId.takeIf { it != -1 } ?: context.tokenPrefs().getInt(PENDING_SESSION_ID, -1)
    }

    private fun setPendingSessionId(context: Context, sessionId: Int) {
        cachedSessionId = sessionId
        context.tokenPrefs().edit { putInt(PENDING_SESSION_ID, sessionId) }
    }

    fun clearPendingSessionId(context: Context, sessionId: Int) {
        if (pendingSessionId(context) != sessionId) return
        cachedSessionId = -1
        context.tokenPrefs().edit { remove(PENDING_SESSION_ID) }
    }
}

sealed interface TvUpdateState {
    object Hidden : TvUpdateState
    object Checking : TvUpdateState
    object UpToDate : TvUpdateState
    object Failed : TvUpdateState
    class Available(val info: TvUpdater.UpdateInfo) : TvUpdateState
    class Downloading(val progress: Float) : TvUpdateState
    object Installing : TvUpdateState
}

/**
 * Drives the update panel of a screen: manual checks show every step, automatic ones only show up when there is an update.
 */
class TvUpdateController(private val activity: ComponentActivity) {

    var state by mutableStateOf<TvUpdateState>(TvUpdateState.Hidden)
        private set

    private var downloadJob: Job? = null

    fun check(manual: Boolean) {
        if (manual) state = TvUpdateState.Checking
        activity.lifecycleScope.launch {
            state = when (val result = TvUpdater.check(activity)) {
                is TvUpdater.CheckResult.Available -> TvUpdateState.Available(result.info)
                TvUpdater.CheckResult.UpToDate -> if (manual) TvUpdateState.UpToDate else TvUpdateState.Hidden
                TvUpdater.CheckResult.Failed -> if (manual) TvUpdateState.Failed else TvUpdateState.Hidden
            }
        }
    }

    fun download(info: TvUpdater.UpdateInfo) {
        state = TvUpdateState.Downloading(0f)
        downloadJob?.cancel()
        downloadJob = activity.lifecycleScope.launch {
            val apk = TvUpdater.download(activity, info) { progress ->
                state = TvUpdateState.Downloading(progress)
            }
            // copying the APK into the installer session is disk I/O, keep it off the main thread
            val installed = apk != null && withContext(Dispatchers.IO) { TvUpdater.install(activity, apk) }
            state = if (installed) {
                TvUpdateState.Installing
            } else {
                TvUpdateState.Failed
            }
        }
    }

    /**
     * Closing an available update means "not now": that build (and older ones) won't be offered again,
     * newer uploads will. Closing a download cancels it.
     */
    fun dismiss() {
        when (val current = state) {
            is TvUpdateState.Available -> activity.tokenPrefs().edit { putLong(C.UPDATE_LAST_CHECKED, current.info.publishedAt) }
            is TvUpdateState.Downloading -> downloadJob?.cancel()
            else -> Unit
        }
        downloadJob = null
        state = TvUpdateState.Hidden
    }
}

@Composable
fun TvUpdatePanel(controller: TvUpdateController) {
    val state = controller.state
    if (state == TvUpdateState.Hidden) return
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    BackHandler { controller.dismiss() }
    LaunchedEffect(state::class) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .clip(RoundedCornerShape(16.dp))
                .background(TvColors.Surface)
                .padding(32.dp),
        ) {
            val title = when (state) {
                TvUpdateState.Checking -> stringResource(R.string.tv_update_checking)
                TvUpdateState.UpToDate -> stringResource(R.string.tv_update_up_to_date)
                TvUpdateState.Failed -> stringResource(R.string.tv_update_error)
                is TvUpdateState.Available -> stringResource(R.string.tv_update_available)
                is TvUpdateState.Downloading -> if (state.progress >= 0) {
                    stringResource(R.string.tv_update_downloading, (state.progress * 100).toInt())
                } else {
                    stringResource(R.string.tv_update_downloading_unknown)
                }
                TvUpdateState.Installing -> stringResource(R.string.tv_update_installing)
                TvUpdateState.Hidden -> ""
            }
            Text(text = title, color = TvColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            when (state) {
                is TvUpdateState.Available -> {
                    val info = state.info
                    Text(
                        text = stringResource(
                            R.string.tv_update_details,
                            TvUpdater.formatDateTime(context, info.publishedAt),
                            info.size?.let { Formatter.formatShortFileSize(context, it) } ?: "?",
                        ),
                        color = TvColors.TextSecondary,
                        fontSize = 16.sp,
                    )
                    info.notes?.let { notes ->
                        Spacer(Modifier.height(16.dp))
                        Text(text = stringResource(R.string.tv_update_whats_new), color = TvColors.Accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = notes,
                            color = TvColors.Text,
                            fontSize = 14.sp,
                            maxLines = 10,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PillButton(
                            text = stringResource(R.string.tv_update_download),
                            onClick = { controller.download(info) },
                            modifier = Modifier.focusRequester(focus),
                        )
                        PillButton(text = stringResource(R.string.tv_update_later), onClick = { controller.dismiss() })
                    }
                }
                is TvUpdateState.Downloading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(TvColors.SurfaceFocused),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(state.progress.coerceIn(0f, 1f))
                                .height(8.dp)
                                .background(TvColors.Accent),
                        )
                    }
                }
                TvUpdateState.Checking -> RefreshBar(visible = true)
                else -> {
                    Spacer(Modifier.height(12.dp))
                    PillButton(
                        text = stringResource(R.string.tv_close),
                        onClick = { controller.dismiss() },
                        modifier = Modifier.focusRequester(focus),
                    )
                }
            }
        }
    }
}
