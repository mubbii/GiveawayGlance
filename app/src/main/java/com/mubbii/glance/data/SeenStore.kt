package com.mubbii.glance.data

import android.content.Context

/**
 * Tracks the last-seen item id per source so the UI can badge a card "NEW"
 * even though we're only fetching on-demand (app open / pull-to-refresh),
 * not running a background monitor. This is the on-device equivalent of the
 * "last seen thread/post" state your desktop scripts keep between runs.
 */
class SeenStore(context: Context) {
    private val prefs = context.getSharedPreferences("seen_store", Context.MODE_PRIVATE)

    fun lastSeenId(sourceKey: String): String? = prefs.getString(sourceKey, null)

    fun markSeen(sourceKey: String, id: String) {
        prefs.edit().putString(sourceKey, id).apply()
    }
}
