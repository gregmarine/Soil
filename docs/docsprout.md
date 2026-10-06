# Docsprout

The second Sprout app: typed documents, built over Soil. Its own APK,
`com.symmetricalpalmtree.soil.docsprout` (`.dev` in debug), a module of this repository. A
document is an item of the kind `document`, its own `.soil` file, in the same folders as
notebooks. The file stores Markdown and nothing else.

It starts from Notesprout SN's document editor and changes one large thing: the default mode is
the document itself, rendered and editable, and it never shows a Markdown marker. The Markdown
text editor is the second mode.

## Reaching it

Through the library, through the side menu's row for it, and through its own drawer icon, which
reopens the document last open, or goes to the library when there is none. **New document**
sits after New notebook on Home: it asks for the name first, pre-filled from the folder's name
scheme, and Soil makes the file in the folder showing and opens it. No folder is closed to
documents. The default template is a notebook's affair and does not apply.

## The file

One table, `document(id, parentId, type, createdAt, updatedAt, deletedAt, text, refId)`: a root
row, and a `body` row whose `text` is the whole document as Markdown (`DocumentSchema`,
`DocumentSql`, `DocumentStore`). A document may be 5 MiB of UTF-8, a little under what the seam
carries in one value (`DocumentLimits`). A save writes the body and, in the same batch, the
file's link mirror (`links.md`), so the two cannot disagree. None of a document's words, and
none of its addresses, is ever logged: lengths only.

## The screen

`DocumentActivity`. It holds the item through an `ISeamItem`, parked at `onStop` and resumed on
return, as a notebook does. Every save, park, resume and close goes through one queue, so they
never overlap. Autosave runs two seconds after the last change, and always on pause and on
leaving; a blank document is saved as blank, not deleted. The card's cover is the opening words
(`TextCover`).

There is no pen and no paper here: the app attaches no client, so the shell's key filter stays
on and the side bars work as over any app.

### Two modes, one truth

| Mode | Surface | What it shows |
|---|---|---|
| Rendered (the default) | `RichEditText` | The document as it reads. No markers, ever |
| Markdown | A plain editor | The stored text |

Ctrl+P, or the button on the bar, switches. Switching writes or parses once and carries the
cursor across by block. **A document opened and not edited is never rewritten**: the rendered
mode writes canonical Markdown (`-` bullets, `**bold**`, `_italic_`, `~~strike~~`, the fewest
escapes that keep the words literal), so an imported file is only put into that form when it is
actually edited there.

### What a document can hold

Notesprout SN's set as it was: headings, bullet, numbered and task lists with nesting, quotes,
rules, bold, italic, strikethrough, inline code and links. Tables, fenced code and images are
not supported yet (`BACKLOG.md`), and nothing of them is lost: table rows and code fences are
kept as raw lines, shown monospace, edited as plain text and written back untouched; image
syntax stays the literal text it is.

### The rich model

In `:markdown`, package `rich`, pure Kotlin and tested on the JVM: `RichDoc` is blocks
(`RichBlock` with a `RichAttr`: paragraph, heading, list item, quote, rule, raw), each holding
its words and style runs (`RichSpan`). `RichParse` reads Markdown, `RichWrite` writes it, and
the two round-trip (`parse(write(doc)) == doc`, held over awkward text and 400 generated
documents). `RichRules` is the editing rules, `RichTyping` type-to-format, `RichPlain` the
plain words.

### The rendered editor

An `EditText` whose text is only the visible characters. Each paragraph carries one `BlockSpan`
(its type, margin, line height and the bullet, number or task box drawn in the margin; a task
box takes a tap); bold, italic, strike, code and link are character spans. `RichCodec` moves
between the view and the model, `RichOps` applies the rules, and `RichHistory` is the app's own
undo and redo, since the platform's does not restore spans (Ctrl+Z and Ctrl+Y are taken here
and left to the platform in the Markdown editor).

**Type-to-format.** A line start (`# `, `- `, `1. `, `- [ ] `, `> `) and a closed inline pair
(`**`, `*`, `~~`, `` ` ``) become the format as they are typed. One undo puts the typed
characters back.

### The tools

The format bar and its overflow: undo, redo, H1 to H3, bold, italic, strikethrough, code, quote,
bullet, numbered, task, outdent, indent, link, image (the Markdown for one, as text), rule, paste handwriting,
find and replace, word count, reflow, proofread. Five text sizes, remembered. The cursor is
remembered per document. A tap on the title renames. The Ctrl shortcuts are SN's
(`EditorShortcuts`).

### Proofread

Spelling by SymSpell (`SymSpellKt` 3.4.0, the one dependency this effort added) over a bundled
English dictionary, and a small set of grammar rules, underlined in both modes, with
suggestions, Ignore and Add to dictionary on a sheet. Inline code and raw lines are not checked.
The switch and the person's own words are per device, in Docsprout's own preferences
(`ProofreadStore`); moving them into Soil is in `BACKLOG.md`.

### Links

`links.md`. In short: a link is a web address, a `soil:` address chosen from the library, or a
`bible:` address, which a Bible reference in the words becomes on its own after a pause in
typing; in the rendered document a tap follows it at once and a long press offers Open, Edit
link and Remove link; a button lists what links to this document, only when something does. A
removed reference is remembered with the document; Relink Bible on the selection's menu puts it
back.

### A Bible passage

**Insert a Bible passage**, on the format bar: a reference typed, its verses read from
Biblesprout through Soil (`passageText`), and put in at the caret under a link to the passage, a
paragraph per chapter run. A passage copied in the reader pastes the same way, asked each time
whether the reference alone goes in (`clipboard.md`).

### Handwriting as words

A document has no ink. Handwriting reaches it two ways, both read by the recogniser chosen in
Settings behind the consent flow (`extensions.md`):

- **Paste.** Ink copied in the Scratch Pad, or lassoed and copied in a notebook, is pasted as
  words at the cursor, from the Paste tool or with Ctrl+V (`clipboard.md`).
- **Convert**, from a notebook, below.

## Convert, in Notesprout

A notebook's page sheet has **Convert page to document** and **Convert notebook to document**.
`ConvertPlan` (pure) cuts each page from top to bottom into what is words already (headings,
which keep their level, and text objects, which are Markdown) and the stretches of handwriting
between them, each read as a page. The lines a hand wrapped are joined into paragraphs, and the
words go in as words: anything in them that would read as markup is escaped. Soil then has the
document's app make the document beside the notebook (`makeItemFromFile`, the maker an import
of a picked file uses), named after the notebook, or "name - page N" for one page.

Done once. The notebook is not changed and nothing is kept in step. A page that cannot be read
stops the convert and leaves nothing behind. The result says where the document is and offers
to open it. Leaving a link to the new document on the notebook page (`design.md` §4) is not
built. A sticky note's content and a link's jump do not come across.

A short last line, a word or two wrapped under a full one, is a line of its own to the
recogniser's segmenter (`:ext-mlkit`, `StrokeSegmenter`): found at this phase's walk, where
such a word was lost into the line above.

## What it gives Soil

`export.RenderService`, answering `IItemRenderer` (`export.md`): the document laid out in pages
for the extensions that take pages; Markdown, plain text and a PDF with real text written by
the app itself; a picked `.md`, `.markdown` or `.txt` taken in as a new document; and the
statements that relabel a file for an import.

## Device-local

`DocsproutPrefs`: the document last open, the text size, each document's cursor, and the trail
of documents followed through links. `ProofreadStore`. Ids only, never a name, never backed up.

## Testing

The model and its rules are JVM tests. The rendered editor's rules need a real `Editable`, so
the debug build carries `SelfTestActivity`, which runs them on the device and reports to logcat
(`building.md`). Add a case there for every editor rule changed.

Three traps met on the Nomad, each guarded in the code:

- A zero-width `getSpans(p, p, …)` does not answer a span that starts at `p`. Always ask over a
  width (`RichCodec.around`).
- A span with an inclusive end grows over text appended after it. Styles are set after all the
  text is built, typed pairs close with an exclusive end, and no style crosses a line break.
- A `LineHeightSpan`'s change to a first line's metrics is handed on to the next line and must
  be put back.

And SN's rules for a Ratta keyboard hold: the IME is never hidden, keyboard attach is handled
without a restart, panels sit in the flow, nothing is disabled (hidden, or a toast).

## Decisions (Greg, 2026-10-04)

- Markers are never shown in the rendered mode.
- SN's Markdown set as is; tables, code blocks and images wait.
- Type-to-format on line starts and on closed inline pairs.
- In scope: import of `.md` and `.txt`, Proofread, handwriting by both routes, links both ways.
- Bible lookup waits for Biblesprout. **Done 2026-10-05**, as Insert a Bible passage, with
  references linked as they are typed (Greg's decisions in `biblesprout.md`).
- The export page size is chosen at export and remembered.
- Document templates wait, as two ideas: paper behind a document, and starter content.
- New document asks for the name first.
- The Proofread dictionary is per device for now.
- No password on the text PDF in this cut.
- A tap on a link follows at once; a long press gives the sheet.
- The Scratch Pad's Send became a clipboard copy (`clipboard.md`).

Walked on the Nomad, phases 1 to 11, 2026-10-04.
