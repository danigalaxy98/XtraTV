package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.os.Build
import com.github.andreyasadchy.xtra.BuildConfig
import java.io.File
import java.util.Date

/**
 * Saves the stack trace of an uncaught exception so it can be shown on the TV the next time the app opens,
 * since there is usually no cable or logcat available on a TV.
 */
object TvCrashLog {

    private const val FILE_NAME = "last_crash.txt"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                file.writeText(
                    buildString {
                        append(Date().toString()).append('\n')
                        append("Xtra ").append(BuildConfig.VERSION_NAME)
                        append(" · Android ").append(Build.VERSION.RELEASE)
                        append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                        append(throwable.stackTraceToString())
                    }
                )
            } catch (e: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun read(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        return try {
            file.takeIf { it.exists() }?.readText()?.let { summarize(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }

    /** Keeps the header, the first lines of the trace and every "Caused by" block, so it fits on screen. */
    private fun summarize(text: String): String {
        val lines = text.lines()
        val result = mutableListOf<String>()
        lines.take(12).forEach { result.add(it) }
        lines.forEachIndexed { index, line ->
            if (index >= 12 && line.trimStart().startsWith("Caused by")) {
                result.add("…")
                lines.subList(index, minOf(index + 6, lines.size)).forEach { result.add(it) }
            }
        }
        return result.joinToString("\n")
    }
}
