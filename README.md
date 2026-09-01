# Giveaway Glance

One-screen Android app: open it, it fetches the latest state of each
giveaway source live (no background monitoring, no server), and shows
them as cards with a NEW badge if it's different from what you saw last
time. Tap a card to open the actual page.

Currently wired up:
- **r/gog Weekly Code Giveaway thread** — same idea as your PRAW script:
  searches r/gog for the weekly thread and takes the newest match.
- **GOG Forum "Free (temporary) keys giveaway central topic."** — fetches
  the thread (which auto-redirects to its current last page) and finds
  the highest post-id permalink on it.

## How to build it

You'll need Android Studio (free, from developer.android.com) — I can't
compile an APK from where I'm running, so this is a real Android Studio
project you open and hit Run on:

1. Open Android Studio → **Open** → select the `GiveawayGlance` folder.
2. Let Gradle sync (first sync downloads dependencies — needs internet).
3. Plug in your phone (USB debugging on) or use an emulator → **Run**.

That's it — no server, no signing needed for your own device.

## Known rough edges to expect

- **Reddit's JSON endpoint** (`www.reddit.com/*.json`) is used anonymously
  here, same as always the simplest option, but Reddit has been
  tightening rate limits / anti-bot checks on it. If you start getting
  empty results or 403s, the fix is switching `RedditRepository` to real
  OAuth (a Reddit "script" app + client id/secret, same auth style PRAW
  already uses on desktop) instead of the anonymous endpoint.
- **GOG post-text extraction is a heuristic.** The "find the latest post
  id" part is solid (confirmed against the live thread), but pulling a
  clean text snippet out of the surrounding HTML is a best-effort walk
  up the DOM (`extractSnippet` in `GogRepository.kt`). If snippets look
  empty or garbled, open the thread in desktop Chrome → View Source,
  find the div wrapping one post, and tighten that function to match —
  same kind of tweak you already made porting IndieGala's `data-content`
  detection into the GOG script.
- **"NEW" tracking is per-device, local only** (`SeenStore.kt`, just
  SharedPreferences) — nothing is synced anywhere, which is fine since
  there's no server in this design.

## Adding the next source (Lenovo, IndieGala, etc.)

Each source is just: a `xRepository.fetchLatest(): GiveawayItem?` class,
one line added to `sourceKeyFor()` in `GlanceViewModel.kt`, and it shows
up in the feed automatically — the UI doesn't care how many sources
there are. Reddit and GOG are meant to be the template to copy.
