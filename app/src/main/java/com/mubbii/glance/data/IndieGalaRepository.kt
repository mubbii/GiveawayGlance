package com.mubbii.glance.data

import com.mubbii.glance.model.GiveawayItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Scrapes an IndieGala (XenForo-based) forum thread. Reused for both
 * "Giveaways Arena" and "Keydrops Go Here" — same forum software, same URL
 * shape, only the thread URL differs.
 *
 * Selectors below were built directly from real saved HTML of both threads
 * (not guessed), so this is on much firmer ground than earlier versions:
 * - each post is `<article data-content="post-<id>">`
 * - author name is inside `.message-name a`
 * - post time is a real `<time class="u-dt" title="...">` element — this is
 *   what actually powers the "NEW" timestamp shown on the card, not just an
 *   incidentally-grabbed string
 * - the post body text is inside `.message-body .bbWrapper`
 * - pagination is `nav.pageNavWrapper .pageNav-main li.pageNav-page a` —
 *   XenForo (unlike GOG's forum) does NOT redirect a bare thread URL to its
 *   last page, so this does one extra fetch first: load page 1, read its
 *   pagination bar to find the last page number, then fetch that page.
 */
class IndieGalaRepository(
    override val key: String,
    override val name: String,
    private val threadUrl: String // e.g. https://forums.indiegala.com/threads/keydrops-go-here.1207
) : GiveawaySource {

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"
    }

    override fun fetchLatest(): List<GiveawayItem> {
        val firstPage = fetch(threadUrl)

        val pageLinks = firstPage.select("nav.pageNavWrapper .pageNav-main li.pageNav-page a")
        val lastPageNum = pageLinks.lastOrNull()?.text()?.trim()?.toIntOrNull()

        // No pagination bar at all, or it points back at page 1: the thread
        // is short enough that page 1 IS the last page.
        val targetDoc = if (lastPageNum == null || lastPageNum <= 1) {
            firstPage
        } else {
            fetch("$threadUrl/page-$lastPageNum")
        }

        val posts = targetDoc.select("article[data-content]").filter { el ->
            el.attr("data-content").startsWith("post-")
        }
        if (posts.isEmpty()) return emptyList()

        val latestPost = posts.maxByOrNull { el ->
            el.attr("data-content").removePrefix("post-").toLongOrNull() ?: -1L
        } ?: return emptyList()

        val postId = latestPost.attr("data-content").removePrefix("post-")
        val author = latestPost.select(".message-name a").firstOrNull()?.text()?.trim()
            ?: "unknown"
        val timeEl = latestPost.select("time.u-dt").firstOrNull()
        val postedAt = timeEl?.attr("title")?.takeIf { it.isNotBlank() }
        val bodyText = latestPost.select(".message-body .bbWrapper").firstOrNull()
            ?.text()?.trim()?.take(240)
            ?: "Tap to view the new post"

        return listOf(
            GiveawayItem(
                sourceName = name,
                id = postId,
                title = "New post by $author",
                snippet = bodyText,
                url = "$threadUrl/post-$postId",
                postedAt = postedAt
            )
        )
    }

    private fun fetch(url: String): Document =
        Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(15_000)
            .get()
}
