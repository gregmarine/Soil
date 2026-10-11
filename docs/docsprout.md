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
leaving; a blank document is saved as blank, not deleted. The rendered document is read on the
main thread and written as Markdown off it, in the queue. A save that fails is tried again while
the screen is up; on the way out it is tried once more and, failing that, a message says the
latest changes were not saved. Export goes ahead only once the file holds the words; otherwise
the document stays open and says so. Words that arrive after the screen stopped (a paste being
read) are not put in. The card's cover is the opening words (`TextCover`).

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
not supported yet (`BACKLOG.md`), and nothing of them is lost: table rows, fenced code (three or
more backticks or tildes with no more of that character after them on the line, closed by a run
of the same character at least as long) and indented
code (four spaces or a tab, after a blank line and not under a list item) are kept as raw lines,
shown monospace, edited as plain text and written back untouched; image syntax stays the literal
text it is. A fence never closed runs to the end of the document. A raw line edited out of its
shape (a table row that lost its `|`, a fence whose closing line is gone with words after it) is
written as a paragraph of its words, escaped, so it can never read back as another block or
swallow what follows. Which lines are code is one rule (`MarkdownCode`), shared by the rendered
editor, the list renumbering, reflow and the proofread; a notebook's text box keeps its own
reading, with no code, since its boxes were measured by it.

The canonical form is a rewrite, not a copy: when a document is edited in the rendered mode,
soft and hard line breaks inside a paragraph, setext headings, lazy continuation lines and a
quote's separate lines are written in the one canonical hand (a paragraph on one line, an ATX
heading, a quote as one line).

### The rich model

In `:markdown`, package `rich`, pure Kotlin and tested on the JVM: `RichDoc` is blocks
(`RichBlock` with a `RichAttr`: paragraph, heading, list item, quote, rule, raw), each holding
its words and style runs (`RichSpan`). `RichParse` reads Markdown, `RichWrite` writes it, and
the two round-trip (`parse(write(doc)) == doc`, held over awkward text and 400 generated
documents). `RichRules` is the editing rules, `RichTyping` type-to-format, `RichPlain` the
plain words. Which blocks sit tight is one rule, `RichRules.tight`, shared by the writer, the
plain text and the rendered editor's spacing: an item under an item of the same list, and a
raw line under a raw line. A bullet or task at the same depth as the numbered item before it
is a new list (as the numbering counts it), written a blank line apart and shown with the full
gap; so is a numbered item under a bullet. A nested list of the other kind stays with its
list, and so does an item that comes back out to a depth whose kind it keeps (2026-10-10).

### The rendered editor

An `EditText` whose text is only the visible characters. Each paragraph carries one `BlockSpan`
(its type, margin, line height and the bullet, number or task box drawn in the margin; a task
box takes a tap); bold, italic, strike, code and link are character spans. `RichCodec` moves
between the view and the model, `RichOps` applies the rules, and `RichHistory` is the app's own
undo and redo, since the platform's does not restore spans (Ctrl+Z and Ctrl+Y are taken here
and left to the platform in the Markdown editor). It keeps a hundred steps, fewer when the
documents held come to four million characters. A link the reference pass makes is a step of
its own that leaves the redo steps alone. After an undo or a redo the pass reads the whole
document again, skipping the links an undo took off while the screen is up, so a link undone
stays undone and a redo that put back words from before a link has them linked again. A rule's place in the text is held by one private-use character
(U+E000), never drawn and never copied: Copy and Cut leave it off the clipboard. A zero-width
space a writer put in is kept. Enter in an empty list item or quote ends the list or quote,
and Enter in an empty paragraph does nothing: Markdown holds no empty paragraph, and a second
Return under a paragraph is a plain-text habit, so what is seen is what is saved (2026-10-10).

Words a tool puts in (a reference, a passage, handwriting, the image skeleton) are plain: they do
not take the style the caret stood at the end of, and a link put over them replaces any other
link there. Typing at the end of a link still extends it, as typing at the end of any style
does.

**Type-to-format.** A line start (`# `, `- `, `1. `, `- [ ] `, `> `) and a closed inline pair
(`**`, `*`, `~~`, `` ` ``) become the format as they are typed. An opening marker inside code
or a link's words is a character, never the start of a pair. One undo puts the typed
characters back.

### The tools

The format bar, every tool on the glass, wrapped onto further rows when the width needs it
(the toolbar round, below): undo, redo, Heading, bold, italic, strikethrough, code, quote,
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
removed reference (from the long-press sheet or the Link dialog's Remove) is remembered with the
document; Relink Bible on the selection's menu puts it back. A reference is never linked across
a line break.

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

## The Sproutscape round (2026-10-10)

The leave button is the arrow, labelled Back, as on every other screen; the X stays only on the
find bar's close. The trail button (back to the document a link was followed from) wears
Tabler's `arrow-bar-to-left`, since the plain arrow is now the leave and `arrow-back-up` is
Undo (proposed). The document's name stays on the top bar: it has no bottom bar to move to. The
app is **Document** to the person; the package and the module keep their names.

## The toolbar round (2026-10-10)

The format bar shows every tool: what does not fit on the first row wraps onto rows below it,
in flow and always shown (`FormatBarRows`; before this the tail sat behind a `…` in a panel
that opened on a tap). A row never begins or ends with a group rule. On the Nomad that is three
rows.

H1 to H3 left the bar for one **Heading** button. Its menu is a bordered column of H1 to H6
hung under the button (`HeadingMenu`, a non-focusable `PopupWindow`, since the document's root
is a column in flow with nothing to float a bar in): the editor keeps its caret and selection,
the keyboard stays, and a pick or a tap outside takes it down, the outside tap still landing.
The chords Ctrl+1 to Ctrl+6 are unchanged. The column lies over the bar's lower rows while it
is open (proposed; it could open beside the button or push the rows down).

The bar **wears what the caret is on** (`FormatState`, `FormatActions.state`): Heading, quote,
bullet, numbered, task, bold, italic, strikethrough and code are bordered when their state
holds, and the Heading menu opens with the caret's level bordered. Read, never cached, at every
caret move on either surface, after every tool and at open. The rendered editor answers from
its spans and the pending styles the last press left; the source answers from the line's
marker (`MarkdownFormatter.blockAt`) and the markers wrapping the selection or the word
(`MarkdownFormatter.inlineOn`, exact to the marker, so `**bold**` is bold and not italic). Over
a selection a style lights only when the whole of it carries the style, which is the state a
press would take off. Actions (undo, link, search and the rest) never light.
