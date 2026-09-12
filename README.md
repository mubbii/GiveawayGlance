# Giveaway Glance

One-screen Android app: open it, it fetches the latest state of each
source live (no background monitoring, no server), and shows results as
cards with a NEW badge if something's changed since you last looked.
Tap a card to open the actual page.

Currently wired up:
- **r/gog Weekly Code Giveaway thread**
- **GOG Forum: Ninja Giveaway 2.0**
- **IndieGala Forum: Giveaways Arena**
- **IndieGala Forum: Keydrops Go Here**
- **Lenovo Game Key Drops** — different shape from the others: shows every
  currently Active or Coming Soon drop with its real start date/time (or
  "date not announced yet" if Lenovo hasn't published one), instead of a
  NEW-post badge. This is the one that solves "I keep forgetting to check
  when the next drop is."

## How to build it

1. Open Android Studio → **Open** → select the `GiveawayGlance` folder
   (or push to GitHub and let your Actions workflow build it).
2. Let Gradle sync (first sync downloads dependencies — needs internet).
3. Plug in your phone (USB debugging on) or use an emulator → **Run**.

## Architecture note (why adding a source is easy)

Every source implements one small interface, `GiveawaySource`
(`data/GiveawaySource.kt`): a `key`, a `name`, and a `fetchLatest()` that
returns a list of relevant items (usually one, but Lenovo can return
several at once — e.g. an Active drop and a Coming Soon drop
simultaneously). `GlanceViewModel` just holds a list of these and loops
over it.

Three reusable patterns exist so far:
- `GogForumRepository` — any GOG forum thread (bare thread URL redirects
  to its current last page; permalinks like `.../postNNNNN`).
- `IndieGalaRepository` — any XenForo-based forum (permalinks like
  `.../post-NNNNN`; needs an extra fetch first to find the last page,
  since XenForo doesn't auto-redirect like GOG does).
- `LenovoRepository` — a GraphQL/JSON API source (Bettermode platform).
  This one was reverse-engineered from a real captured browser session:
  it calls `api.bettermode.com`'s `Tokens` query (no login — hands back
  a short-lived guest access token) then `GetPosts` filtered to Lenovo's
  Key Drops post type, reading each drop's `status` and `start_date`
  custom fields directly rather than scraping rendered HTML.

To add a new GOG/IndieGala thread: one more entry in `GlanceViewModel`'s
`sources` list, no new file. To add a source on different software
entirely: copy whichever of the three patterns above is the closest
match.

## Known rough edges to expect

- **Reddit's JSON endpoint** is used anonymously — simplest option, but
  Reddit has tightened rate limits / anti-bot checks on it over time.
  If you start getting empty results or 403s, the fix is real OAuth (a
  Reddit "script" app + client id/secret, same auth style PRAW already
  uses on desktop).
- **GOG snippet-text extraction is a heuristic** (`extractSnippet` in
  `GogForumRepository.kt`) — post-id detection is solid (confirmed
  against the live thread), but the surrounding text pull is a
  best-effort DOM walk, unlike IndieGala's which is selector-based from
  real saved HTML.
- **Lenovo's guest token is short-lived** (observed ~5 minutes in the
  captured session) — that's fine here since a fresh one is fetched on
  every app refresh, but if Bettermode ever changes the `Tokens` query
  shape, that's the first place to check.
- **"NEW" tracking is per-device, local only** (`SeenStore.kt`, just
  SharedPreferences) — nothing is synced anywhere, which is fine since
  there's no server in this design.
