# Giveaway Glance

One-screen Android app: open it, it fetches the latest state of each
source live (no background monitoring, no server), and shows results as
cards with a NEW badge if something's changed since you last looked.
Tap a card to open the actual page.

Currently wired up:
- **r/gog Weekly Code Giveaway thread**
- **GOG Forum: Ninja Giveaway 2.0** — real author/date/body text, built
  from actual saved HTML.
- **IndieGala Forum: Giveaways Arena**
- **IndieGala Forum: Keydrops Go Here**
- **Lenovo Game Key Drops** — different shape from the others: shows
  every currently relevant Active/Coming Soon drop (excluding recurring
  in-game-currency drops, and anything whose date has already passed —
  see note below) with its real start date and a one-time-computed
  countdown, e.g. "Coming Soon — Sep 23, 9:00 PM (in 10d 13h)".

## How to build it

1. Open Android Studio → **Open** → select the `GiveawayGlance` folder
   (or push to GitHub and let your Actions workflow build it).
2. Let Gradle sync (first sync downloads dependencies — needs internet).
3. Plug in your phone (USB debugging on) or use an emulator → **Run**.

## Architecture note (why adding a source is easy)

Every source implements one small interface, `GiveawaySource`
(`data/GiveawaySource.kt`): a `key`, a `name`, and a `fetchLatest()` that
returns a list of relevant items (usually one, but Lenovo can return
several at once). `GlanceViewModel` just holds a list of these and loops
over it.

Three reusable patterns exist so far, each built from real saved HTML/
captured requests rather than guessed selectors:
- `GogForumRepository` — GOG's older forum template. Posts are
  `div.big_post_h`, permalink/id in `div.post_nr a[href]` (`/postNNNNN`,
  no hyphen), date in `div.post_date` ("Posted August 17, 2026"), author
  in `div.b_u_name`, body in `div.post_text_c` (quoted-post blocks
  stripped before reading). Bare thread URL auto-redirects to the
  current last page — no separate pagination fetch needed.
- `IndieGalaRepository` — XenForo-based forum. Posts are
  `article[data-content="post-NNNNN"]` (hyphenated, unlike GOG), author
  in `.message-name a`, real timestamp in `time.u-dt[title]`, body in
  `.message-body .bbWrapper`. XenForo does NOT auto-redirect to the last
  page, so this fetches page 1 first to find the highest page number.
- `LenovoRepository` — a GraphQL/JSON API source (Bettermode platform),
  not a forum at all. Calls `api.bettermode.com`'s `Tokens` query (no
  login — hands back a short-lived guest token) then `GetPosts` filtered
  to Lenovo's Key Drops post type, reading each drop's `status`,
  `start_date`, `end_date` custom fields directly.

To add a new GOG/IndieGala thread: one more entry in `GlanceViewModel`'s
`sources` list, no new file. To add a source on different software
entirely: copy whichever of the three patterns above is the closest
match — and send over real saved HTML/a captured request for it first,
same as these three, rather than guessing at selectors blind.

## Known rough edges to expect

- **Reddit's JSON endpoint** is used anonymously — simplest option, but
  Reddit has tightened rate limits / anti-bot checks on it over time.
  If you start getting empty results or 403s, the fix is real OAuth (a
  Reddit "script" app + client id/secret, same auth style PRAW already
  uses on desktop).
- **Lenovo's `status` field doesn't reliably track real timing** — some
  drops (recurring "restocked" pools) stay marked Active long after
  their listed date passes, since it's set manually on Lenovo's end.
  So `LenovoRepository` ignores `status` for filtering and instead
  compares each drop's own `start_date`/`end_date` to right now, only
  keeping ones that haven't passed. It also skips anything titled with
  "Currency" (see `EXCLUDED_TITLE_KEYWORDS`) since those are recurring
  reward pools, not single-game key giveaways — add more keywords there
  if another such category shows up.
- **Lenovo's guest token is short-lived** (observed ~5 minutes) — fine
  here since a fresh one is fetched on every app refresh.
- **"NEW" tracking is per-device, local only** (`SeenStore.kt`, just
  SharedPreferences) — nothing is synced anywhere.
