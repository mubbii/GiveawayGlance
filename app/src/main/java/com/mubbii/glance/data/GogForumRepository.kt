package com.mubbii.glance.data

import com.mubbii.glance.model.GiveawayItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Scrapes any GOG forum thread of this shape. Confirmed live against the
 * "Free (temporary) keys giveaway central topic." thread: the base thread
 * URL (no page number) redirects to the CURRENT last page automatically,
 * and every post has a permalink like .../postNNNNN where NNNNN is a
 * strictly-increasing post id. So "fetch the base URL, take the highest
 * post id on the resulting page" reliably gives the newest post without
 * tracking page numbers yourself. Ninja Giveaway 2.0 is the same GOG forum
 * software, same URL shape, so this class is reused for both rather than
 * writing a second near-identical one.
 *
 * NOTE ON SNIPPETS: the post-id detection is solid (that part's confirmed),
 * but pulling clean text out of the surrounding HTML (`extractSnippet`) is
 * a best-effort DOM walk since I only had a text-extracted view of the page,
 * not the raw class names. If a snippet looks empty or garbled, open the
 * thread in desktop Chrome -> View Source, find the div wrapping one post,
 * and tighten `extractSnippet` to match it.
 */
class GogForumRepository(
    override val key: String,
    override val name: String,
    private val threadUrl: String
) : GiveawaySource {

    companion object {
        private val POST_ID_REGEX = Regex(""".*/post(\d+)$""")
    }

    override fun fetchLatest(): List<GiveawayItem> {
        // Jsoup follows the redirect to the current last page automatically.
        val doc = Jsoup.connect(threadUrl)
            .userAgent("Mozilla/5.0 (Android) GiveawayGlance/1.0")
            .timeout(15_000)
            .get()

        val postLinks = doc.select("a[href]").filter { el ->
            POST_ID_REGEX.matches(el.attr("href"))
        }
        if (postLinks.isEmpty()) return emptyList()

        val latestLink = postLinks.maxByOrNull { el ->
            POST_ID_REGEX.find(el.attr("href"))!!.groupValues[1].toLong()
        } ?: return emptyList()

        val postId = POST_ID_REGEX.find(latestLink.attr("href"))!!.groupValues[1]
        val snippet = extractSnippet(latestLink)

        return listOf(
            GiveawayItem(
                sourceName = name,
                id = postId,
                title = "New post in giveaway thread",
                snippet = snippet,
                url = latestLink.absUrl("href")
            )
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
