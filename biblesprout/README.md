# Biblesprout

The Bible reader: a Sprout app with no items of its own. Its position and recents live in Soil's
app store, over the seam. The text is the Berean Standard Bible (public domain,
https://bereanbible.com), bundled as `src/main/assets/bible/bsb.bible`, a SQLite file built by
`tools/bible/build_bible_db.py --slim` and copied once to the app's no-backup storage on first open.
The fonts are Noto Serif (`fonts/FONTS.md`).
