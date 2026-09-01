package com.mubbii.glance.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mubbii.glance.data.GogRepository
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
 * phone or PC running 24/7 just to "catch" a new giveaway.
 */
class GlanceViewModel(app: Application) : AndroidViewModel(app) {

    private val client = OkHttpClient()
    private val redditRepo = RedditRepository(client)
    private val gogRepo = GogRepository()
    private val seenStore = SeenStore(app)

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
                runCatching { redditRepo.fetchLatest() }
                    .onSuccess { it?.let(results::add) }
                    .onFailure { errors.add("Reddit: ${it.message ?: "failed to fetch"}") }

                runCatching { gogRepo.fetchLatest() }
                    .onSuccess { it?.let(results::add) }
                    .onFailure { errors.add("GOG Forum: ${it.message ?: "failed to fetch"}") }
            }

            val withNewFlags = results.map { item ->
                val key = sourceKeyFor(item.sourceName)
                val isNew = key != null && seenStore.lastSeenId(key) != item.id
                item.copy(isNew = isNew)
            }

            // Mark everything as seen now that we've shown it once.
            withNewFlags.forEach { item ->
                sourceKeyFor(item.sourceName)?.let { seenStore.markSeen(it, item.id) }
            }

            _state.update {
                it.copy(
                    isRefreshing = false,
                    items = withNewFlags,
                    errors = errors,
                    lastRefreshed = System.currentTimeMillis()
                )
            }
        }
    }

    private fun sourceKeyFor(sourceName: String): String? = when (sourceName) {
        "r/gog Weekly Giveaway" -> RedditRepository.SOURCE_KEY
        "GOG Forum Giveaways" -> GogRepository.SOURCE_KEY
        else -> null
    }
}
