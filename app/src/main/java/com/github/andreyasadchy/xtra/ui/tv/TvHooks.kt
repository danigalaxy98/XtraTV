package com.github.andreyasadchy.xtra.ui.tv

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.navigation.fragment.NavHostFragment
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity

/**
 * Every place where the TV interface plugs into upstream code, so upstream updates merge cleanly:
 * - [openTvInterface] is the first line of MainActivity.onCreate (the only change to upstream code);
 * - [install] runs from [TvInitProvider] and adapts the classic settings screen without changing SettingsActivity.
 */
object TvHooks {

    /** Navigation action to open in the classic settings screen (SettingsActivity). */
    const val EXTRA_CLASSIC_DESTINATION = "tv_classic_destination"

    private val CHANNEL_LOGIN = Regex("^[A-Za-z0-9_]{1,25}$")
    private val RESERVED_PATHS = setOf(
        "videos", "clip", "clips", "directory", "search", "settings", "team", "downloads", "drops",
        "inventory", "wallet", "subscriptions", "following", "friends", "messages", "login", "signup",
        "logout", "jobs", "turbo", "prime", "store", "p", "u", "popout", "embed", "moderator",
        "broadcast", "dashboard", "bits", "privacy", "legal", "activate", "collections",
    )

    /**
     * On TV the phone screens are never shown: links, notifications and restarts that reach MainActivity
     * open the TV interface instead. Returns true when MainActivity has to stop its onCreate.
     */
    fun openTvInterface(activity: Activity): Boolean {
        if (!TvUtils.isTv(activity)) return false
        val intent = activity.intent
        // the classic updater reports the install result to MainActivity
        if (intent?.action == MainActivity.INTENT_INSTALL_UPDATE) return false
        val stream = if (intent?.action == MainActivity.INTENT_LIVE_NOTIFICATION) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(MainActivity.KEY_VIDEO, Stream::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(MainActivity.KEY_VIDEO)
                }
            } catch (e: Exception) {
                null
            }
        } else null
        val login = stream?.channelLogin ?: channelLogin(intent)
        if (login != null || stream?.channelId != null) {
            TvActivity.openChannel(activity, stream?.channelId, login, stream?.channelName)
        } else {
            activity.startActivity(Intent(activity, TvActivity::class.java))
        }
        activity.finish()
        return true
    }

    /** Login of a plain channel link (twitch.tv/<login>), or null for any other intent. */
    private fun channelLogin(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "twitch.tv" && host != "www.twitch.tv" && host != "m.twitch.tv") return null
        val segment = uri.pathSegments?.singleOrNull() ?: return null
        if (segment.lowercase() in RESERVED_PATHS) return null
        return segment.takeIf { CHANNEL_LOGIN.matches(it) }
    }

    fun install(application: Application) {
        if (!TvUtils.isTv(application)) return
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // the classic screens opened from the TV settings need a visible focus
                if (activity is SettingsActivity) {
                    TvUtils.installFocusHighlight(activity)
                }
            }

            override fun onActivityStarted(activity: Activity) {
                if (activity is SettingsActivity) {
                    openClassicDestination(activity)
                }
            }

            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun openClassicDestination(activity: SettingsActivity) {
        val destination = activity.intent.getIntExtra(EXTRA_CLASSIC_DESTINATION, 0)
        if (destination == 0) return
        activity.intent.removeExtra(EXTRA_CLASSIC_DESTINATION)
        try {
            val navController = (activity.supportFragmentManager.findFragmentById(R.id.navHostFragment) as? NavHostFragment)?.navController ?: return
            // a restored screen already shows where the user was
            if (navController.currentDestination?.id == navController.graph.startDestinationId) {
                navController.navigate(destination)
            }
        } catch (e: Exception) {
            // upstream changed its navigation: stay on the main settings screen
        }
    }
}
