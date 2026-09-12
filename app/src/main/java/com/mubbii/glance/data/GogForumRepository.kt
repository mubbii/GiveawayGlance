package com.mubbii.glance.data

import com.mubbii.glance.model.GiveawayItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Scrapes a GOG forum thread. Built from real saved HTML of the "Ninja
 * Giveaway 2.0" thread (not guessed), so selectors are precise:
 * - each real post is `div.big_post_h`
 * - its permalink/id is in `div.post_nr a[href]`, href ending in
 *   `/post<id>` (no hyphen, unlike IndieGala's `/post-<id>`)
 * - the post date is plain text in `div.post_date`, formatted like
 *   "Posted August 17, 2026"
 * - the author name is in `div.b_u_name`
 * - the body text is in `div.post_text_c` — this can contain a nested
 *   quoted-post block (`div.quot`), which is stripped before reading the
 *   text so replies don't just repeat whatever they quoted
 *
 * The base thread URL redirects to the current last page automatically
 * (confirmed live), so this is a single fetch — no separate pagination
 * step needed, unlike IndieGala's XenForo forum.
 */
class GogForumRepository(
    override val key: String,
    override val name: String,
    private val threadUrl: String
) : GiveawaySource {

    companion object {
        private val POST_ID_REGEX = Regex(""".*/post(\d+)$""")
        private val DATE_FORMATTER = DateTimeFormatter
            .ofPattern("'Posted' MMMM d, yyyy", Locale.ENGLISH)
    }

    override fun fetchLatest(): List<GiveawayItem> {
        val doc = Jsoup.connect(threadUrl)
            .userAgent("Mozilla/5.0 (Android) GiveawayGlance/1.0")
            .timeout(15_000)
            .get()

        val posts = doc.select("div.big_post_h")
        if (posts.isEmpty()) return emptyList()

        var bestPost: Element? = null
        var bestId = -1L
        var bestUrl: String? = null

        for (post in posts) {
            val idLink = post.select("div.post_nr a[href]").firstOrNull() ?: continue
            val match = POST_ID_REGEX.find(idLink.attr("href")) ?: continue
            val id = match.groupValues[1].toLongOrNull() ?: continue
            if (id > bestId) {
                bestId = id
                bestPost = post
                bestUrl = idLink.absUrl("href")
            }
        }

        val post = bestPost ?: return emptyList()
        val url = bestUrl ?: threadUrl

        val author = post.select("div.b_u_name").firstOrNull()?.text()?.trim() ?: "unknown"

        val dateText = post.select("div.post_date").firstOrNull()?.text()?.trim()
        val postedAt = dateText?.let { parsePostedDate(it) }

        val bodyEl = post.select("div.post_text_c").firstOrNull()?.clone()
        bodyEl?.select("div.quot")?.remove() // drop quoted-post blocks
        val bodyText = bodyEl?.text()?.trim()?.take(240)
            ?.ifBlank { null } ?: "Tap to view the new post"

        return listOf(
            GiveawayItem(
                sourceName = name,
                id = bestId.toString(),
                title = "New post by $author",
                snippet = bodyText,
                url = url,
                postedAt = postedAt
            )
        )
    }

    /** GOG shows dates like "Posted August 17, 2026" with no time of day. */
    private fun parsePostedDate(text: String): String? =
        try {
            val date = LocalDate.parse(text, DATE_FORMATTER)
            date.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH))
        } catch (e: DateTimeParseException) {
            null
        }
}
