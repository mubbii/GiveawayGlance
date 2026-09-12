package com.mubbii.glance.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mubbii.glance.data.GiveawaySource
import com.mubbii.glance.data.GogForumRepository
import com.mubbii.glance.data.IndieGalaRepository
import com.mubbii.glance.data.LenovoRepository
import com.mubbii.glance.data.RedditRepository
import com.mubbii.glance.data.SeenStore
import com.mubbii.glance.model.GiveawayItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

data class GlanceUiState(
    val isRefreshing: Boolean = false,
    val items: List<GiveawayItem> = emptyList(),
    val errors: List<String> = emptyList(),
    val lastRefreshed: Long? = null
)

/**
 * Nothing here runs in the background. Everything fetches on demand — app
 * open or pull-to-refresh — which is the whole point: no need to keep a
 * phone or PC running 24/7 just to "catch" a new giveaway or a new drop
 * date.
 *
 * TO ADD A NEW SOURCE: write a class implementing GiveawaySource (copy
 * GogForumRepository / IndieGalaRepository for forum-style sources, or
 * LenovoRepository for a GraphQL/JSON API source), then add one line to
 * the `sources` list below. Nothing else in this file, or in the UI,
 * needs to change.
 */
class GlanceViewModel(app: Application) : AndroidViewModel(app) {

    private val client = OkHttpClient()
    private val seenStore = SeenStore(app)

    private val sources: List<GiveawaySource> = listOf(
        RedditRepository(client),
        GogForumRepository(
            key = "gog_ninja_giveaway_20",
            name = "GOG Ninja Giveaway 2.0",
            threadUrl = "https://www.gog.com/forum/general/ninja_giveaway_20"
        ),
        IndieGalaRepository(
            key = "indiegala_giveaways_arena",
            name = "IndieGala Giveaways Arena",
            threadUrl = "https://forums.indiegala.com/threads/giveaways-arena.16782"
        ),
        IndieGalaRepository(
            key = "indiegala_keydrops",
            name = "IndieGala Keydrops Go Here",
            threadUrl = "https://forums.indiegala.com/threads/keydrops-go-here.1207"
        ),
        LenovoRepository(client)
    )

    private val _state = MutableStateFlow(GlanceUiState())
    val state: StateFlow<GlanceUiState> = _state

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true, errors = emptyList()) }

            val results = mutableListOf<GiveawayItem>()
            val errors = mutableListOf<String>()

            withContext(Dispatchers.IO) {
                for (source in sources) {
                    runCatching { source.fetchLatest() }
                        .onSuccess { fetchedItems ->
                            fetchedItems.forEach { item ->
                                // Multiple items from one source (e.g. Lenovo's
                                // Active + Coming Soon drops) share the source's
                                // key but are tracked individually by their id.
                                val seenKey = "${source.key}:${item.id}"
                                val isNew = seenStore.lastSeenId(seenKey) != item.id
                                results.add(item.copy(isNew = isNew))
                                seenStore.markSeen(seenKey, item.id)
                            }
                        }
                        .onFailure { e ->
                            errors.add("${source.name}: ${e.message ?: "failed to fetch"}")
                        }
                }
            }

            _state.update {
                it.copy(
                    isRefreshing = false,
                    items = results,
                    errors = errors,
                    lastRefreshed = System.currentTimeMillis()
                )
            }
        }
    }
}
