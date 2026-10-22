package com.github.andreyasadchy.xtra.ui.tv.upstream

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.ui.settings.SettingsViewModel
import com.github.andreyasadchy.xtra.ui.tv.TvHooks

/**
 * The settings actions of the original that the TV settings reuse, and the way into its classic settings screen.
 * Part of the only layer of the TV interface that touches the original code (see CAMBIOS-TV.md, section 2).
 */
class TvUpstreamSettings(private val activity: ComponentActivity) {

    private val viewModel by lazy {
        ViewModelProvider(activity, SettingsViewModel.SettingsViewModelFactory)[SettingsViewModel::class.java]
    }

    /** Turns the notifications of live streams on or off for the followed channels. */
    fun toggleNotifications(enabled: Boolean) {
        viewModel.toggleNotifications(
            enabled = enabled,
            networkLibrary = TvUpstreamAccount.networkLibrary(activity),
            gqlHeaders = TvUpstreamAccount.gqlHeaders(activity, true),
            helixHeaders = TvUpstreamAccount.helixHeaders(activity),
        )
    }

    fun deleteVideoPositions() = viewModel.deletePositions()

    fun deleteRecentSearches() = viewModel.deleteRecentSearches()

    fun importDownloads() = viewModel.importDownloads()

    companion object {
        /** Opens the classic (phone) settings screen; [destination] is a navigation action of its graph, 0 for the main screen. */
        fun openClassic(activity: Activity, destination: Int) {
            activity.startActivity(Intent(activity, SettingsActivity::class.java).putExtra(TvHooks.EXTRA_CLASSIC_DESTINATION, destination))
        }
    }
}
