package com.github.andreyasadchy.xtra.ui.tv

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Runs once at app start (before Application.onCreate) so the TV build can set itself up
 * without changes to XtraApp. Declared in src/tv/AndroidManifest.xml; it serves no data.
 */
class TvInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { TvPrefs.ensureForkUpdateUrl(it) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
