package com.mubbii.glance.model

/**
 * One "latest thing" from a source (a Reddit thread, a GOG forum post, etc).
 * `id` must be a stable identifier for that source (Reddit thread id,
 * GOG post id) so we can diff it against what was last seen.
 */
data class GiveawayItem(
    val sourceName: String,      // "r/gog", "GOG Forum"
    val id: String,              // stable id used for "is this new?" comparison
    val title: String,           // headline text shown on the card
    val snippet: String,         // secondary line (author, excerpt, date)
    val url: String,             // opens in browser on tap
    val isNew: Boolean = false   // set by the ViewModel after comparing to SeenStore
)

sealed class SourceResult {
    data class Success(val item: GiveawayItem) : SourceResult()
    data class Empty(val sourceName: String) : SourceResult()
    data class Error(val sourceName: String, val message: String) : SourceResult()
}
