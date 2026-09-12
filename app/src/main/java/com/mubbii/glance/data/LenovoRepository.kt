package com.mubbii.glance.data

import com.mubbii.glance.model.GiveawayItem
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Lenovo's "Game Key Drops" page (gaming.lenovo.com/game-key-drops) is a
 * scheduled-event board, not a forum you post in — you can't win anything
 * here, the point is just knowing WHEN the next drop happens, since Lenovo
 * sometimes shows a date and sometimes just says "Coming Soon" with no date
 * yet. So this returns every currently Active or Coming Soon drop (there
 * can be more than one at once), each with its real start date if Lenovo
 * has published one.
 *
 * This was reverse-engineered from a live browser session (not guessed):
 * the page runs on the Bettermode community platform, backed by a GraphQL
 * API at api.bettermode.com. Getting data out takes two calls:
 *
 * 1. `Tokens` query with just `networkDomain` — no login needed, hands
 *    back a short-lived anonymous/guest access token (confirmed live:
 *    the token in the captured session expired in 5 minutes). This is the
 *    same thing the site itself does for a logged-out visitor.
 * 2. `GetPosts` query, sent with that token as `Authorization: Bearer`,
 *    filtered to this specific post type + space + status.
 *
 * The status/space/post-type IDs below are Lenovo's real, fixed IDs
 * (confirmed from a live captured request) — they're opaque internal IDs,
 * not something this app invents or guesses.
 */
class LenovoRepository(private val client: OkHttpClient) : GiveawaySource {

    override val key = "lenovo_key_drops"
    override val name = "Lenovo Game Key Drops"

    companion object {
        private const val API_URL = "https://api.bettermode.com/"
        private const val NETWORK_DOMAIN = "gaming-lenovo-prod.bettermode.io"
        private const val SPACE_ID = "y4nnEocBKMA2"
        private const val POST_TYPE_ID = "tgy4OKncNC5kziW"

        // Real option IDs for the "status" custom field on this post type.
        private const val STATUS_ACTIVE = "AmAI_EO502mWht5Fb6OE0"
        private const val STATUS_COMING_SOON = "X7FhO8Z5w0QXFFnoFHVpZ"

        private const val TOKENS_QUERY = """
            query Tokens(${'$'}networkDomain: String) {
              tokens(networkDomain: ${'$'}networkDomain) {
                accessToken
              }
            }
        """

        private const val GET_POSTS_QUERY = """
            query GetPosts(${'$'}spaceIds: [ID!], ${'$'}postTypeIds: [String!], ${'$'}limit: Int!, ${'$'}orderByString: String, ${'$'}reverse: Boolean, ${'$'}filterBy: [PostListFilterByInput!]) {
              posts(
                spaceIds: ${'$'}spaceIds
                postTypeIds: ${'$'}postTypeIds
                limit: ${'$'}limit
                orderByString: ${'$'}orderByString
                reverse: ${'$'}reverse
                filterBy: ${'$'}filterBy
              ) {
                nodes {
                  id
                  slug
                  title
                  url
                  fields {
                    key
                    value
                  }
                }
              }
            }
        """
    }

    override fun fetchLatest(): List<GiveawayItem> {
        val accessToken = fetchGuestAccessToken() ?: return emptyList()
        return fetchActiveAndUpcomingDrops(accessToken)
    }

    private fun fetchGuestAccessToken(): String? {
        val body = JSONObject().apply {
            put("query", TOKENS_QUERY)
            put("variables", JSONObject().put("networkDomain", NETWORK_DOMAIN))
            put("operationName", "Tokens")
        }

        val response = post(body) ?: return null
        return response
            .optJSONObject("data")
            ?.optJSONObject("tokens")
            ?.optString("accessToken")
            ?.takeIf { it.isNotBlank() }
    }

    private fun fetchActiveAndUpcomingDrops(accessToken: String): List<GiveawayItem> {
        val filterBy = JSONArray().put(
            JSONObject().apply {
                put("keyString", "fields.status")
                put("operator", "in")
                put("value", "[\"$STATUS_ACTIVE\",\"$STATUS_COMING_SOON\"]")
            }
        )
        val variables = JSONObject().apply {
            put("spaceIds", JSONArray().put(SPACE_ID))
            put("postTypeIds", JSONArray().put(POST_TYPE_ID))
            put("limit", 10)
            put("orderByString", "fields.start_date")
            put("reverse", false)
            put("filterBy", filterBy)
        }
        val body = JSONObject().apply {
            put("query", GET_POSTS_QUERY)
            put("variables", variables)
            put("operationName", "GetPosts")
        }

        val response = post(body, accessToken) ?: return emptyList()
        val nodes = response
            .optJSONObject("data")
            ?.optJSONObject("posts")
            ?.optJSONArray("nodes")
            ?: return emptyList()

        val items = mutableListOf<GiveawayItem>()
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            val fields = fieldsMap(node.optJSONArray("fields"))

            val statusId = fields["status"]
            val statusLabel = when (statusId) {
                STATUS_ACTIVE -> "Active"
                STATUS_COMING_SOON -> "Coming Soon"
                else -> null
            }
            val startDateRaw = fields["start_date"]
            val postedAt = startDateRaw?.let { formatDropDate(it) }

            items.add(
                GiveawayItem(
                    sourceName = name,
                    id = node.optString("id"),
                    title = node.optString("title", "Key drop"),
                    snippet = statusLabel?.let { "$it${if (postedAt != null) " — starts $postedAt" else " — date not announced yet"}" }
                        ?: "Tap to view details",
                    url = node.optString("url").ifBlank {
                        "https://gaming.lenovo.com/game-key-drops/post/${node.optString("slug")}"
                    },
                    postedAt = postedAt
                )
            )
        }
        return items
    }

    private fun fieldsMap(fields: JSONArray?): Map<String, String> {
        if (fields == null) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (i in 0 until fields.length()) {
            val field = fields.getJSONObject(i)
            map[field.optString("key")] = field.optString("value")
        }
        return map
    }

    /** Lenovo's start_date custom field is stored as an ISO-8601 instant. */
    private fun formatDropDate(raw: String): String? {
        return try {
            val instant = Instant.parse(raw)
            val formatter = DateTimeFormatter.ofPattern("MMM d, h:mm a")
                .withZone(ZoneId.systemDefault())
            formatter.format(instant)
        } catch (e: DateTimeParseException) {
            null // fall back to no date rather than showing a raw/garbled string
        }
    }

    private fun post(body: JSONObject, accessToken: String? = null): JSONObject? {
        val requestBuilder = Request.Builder()
            .url(API_URL)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (accessToken != null) {
            requestBuilder.header("Authorization", "Bearer $accessToken")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val responseBody = response.body?.string() ?: return null
            return JSONObject(responseBody)
        }
    }
}
