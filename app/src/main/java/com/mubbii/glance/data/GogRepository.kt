package com.mubbii.glance.data

import com.mubbii.glance.model.GiveawayItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Scrapes the GOG "Free (temporary) keys giveaway central topic." thread.
 *
 * The thread URL with no page number redirects to whatever the CURRENT last
 * page is (confirmed live: /free_temporary_keys_giveaway_central_topic ->
 * .../page679), and every post has a permalink like .../post10239 where the
 * trailing number is a strictly-increasing post id. So "fetch the base URL,
 * find the highest post id on the resulting page" reliably gives you the
 * newest post without needing to track page numbers yourself.
 *
 * NOTE ON SELECTORS: GOG's forum is server-rendered (good - no JS execution
 * needed), but I couldn't inspect the raw HTML/class names directly from
 * here, only a text-extracted view of the page. The permalink-based id
 * detection below is solid, but the exact snippet-of-post-text extraction is
 * a best-effort heuristic. If it comes back blank/garbled once you run this
 * for real, open the page in Chrome desktop mode > View Source, find the
 * container div around a single forum post, and adjust `extractSnippet`
 * to match its actual class name — this is the same kind of tweak you
 * already made to the IndieGala/GOG desktop scripts (data-content id
 * detection, etc).
 */
class GogRepository {

    companion object {
        const val SOURCE_KEY = "gog_forum_giveaway_thread"
        private const val SOURCE_NAME = "GOG Forum Giveaways"
        private const val THREAD_URL =
            "https://www.gog.com/forum/general/free_temporary_keys_giveaway_central_topic"
        private val POST_ID_REGEX = Regex(""".*/post(\d+)$""")
    }

    fun fetchLatest(): GiveawayItem? {
        // Jsoup follows the redirect to the current last page automatically.
        val doc = Jsoup.connect(THREAD_URL)
            .userAgent("Mozilla/5.0 (Android) GiveawayGlance/1.0")
            .timeout(15_000)
            .get()

        val postLinks = doc.select("a[href]").filter { el ->
            POST_ID_REGEX.matches(el.attr("href"))
        }
        if (postLinks.isEmpty()) return null

        val latestLink = postLinks.maxByOrNull { el ->
            POST_ID_REGEX.find(el.attr("href"))!!.groupValues[1].toLong()
        } ?: return null

        val postId = POST_ID_REGEX.find(latestLink.attr("href"))!!.groupValues[1]
        val snippet = extractSnippet(latestLink)

        return GiveawayItem(
            sourceName = SOURCE_NAME,
            id = postId,
            title = "New post in giveaway thread",
            snippet = snippet,
            url = latestLink.absUrl("href")
        )
    }

    /** Best-effort: walk up from the post-id anchor to a reasonably sized text block. */
    private fun extractSnippet(anchor: Element): String {
        var node: Element? = anchor
        repeat(6) {
            node = node?.parent()
            val text = node?.text()?.trim().orEmpty()
            if (text.length in 20..400) return text
        }
        return node?.text()?.take(200)?.trim().orEmpty().ifBlank { "Tap to view the new post" }
    }
}
