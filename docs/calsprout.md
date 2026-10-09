# Calsprout

The calendar: the fourth Sprout app, and the second with no items of its own. Notesprout SN's
`NSE · Calendar` extension brought across whole and put in Soil's shape: the same three
writable pages (Month, Week, Day in two halves), the same events with their editor, list,
recurrence, reminders as a look-ahead, notes and glyphs, with its store in Soil's app store
over the seam instead of a host's, found by Soil as a Sprout app, and with Send replaced by the
clipboard, a notebook page clip for a whole page, Soil's export screen for export, and Soil's
link index for a day as a link target.

## The parts

| Module | What |
|---|---|
| `:calsprout` | The app: `CalendarActivity` on `:paper`'s `InkScreenActivity`, `CalendarDocument` over `CalendarStore`, the geometry and the template, the navigation, `EventsActivity` and `EventEditorActivity` with their dialogs, `EventStore` and the recurrence engine, `RenderService`, `RenderKey`, `CalLinksModel` |
| `:paper` | Shared since this effort: `CalendarDates`, `CalendarTarget`, the day picker (`DayPickerDialog`, `DayPickerModel`), the lasso popup (`LassoPopup`), where pasted ink lands (`InkPlacement`), the backlinks panel (`BacklinksPanel`, `BacklinksModel`), the calendar icons |
| `:seam-kit` | `InkClip.pageEnvelopeOf`: a page, or several, as a notebook page clip |
| `:seam` | `CalAddress` (`cal:<yyyy-MM-dd>`), `SeamLinks.PUT_CAL` and the `calDate` column, `calBacklinks` and `SeamCalBacklink`, `ACTION_OPEN_CALENDAR`, `EXTRA_CAL_DATE`, the export screen's render-only extras |
| `:soil` | `ItemApps.findCalendar` and `openCalendar`, `FollowLinkActivity`'s day branch, the export screen's render-only mode (`ExportRenderMode`), the index's `calDate` (step 11), `AppStoreLease.MAX_BATCH` at the seam's cap, `AppStores` migrating a cached store |
| Notesprout, Docsprout | The day as a link target: a fifth link kind and the picker's fourth shelf; **Choose a day** on the Link dialog; Paste page over several pages |

## Reaching it

Calsprout's icon, and its row in Soil's side menu, open the calendar where it was left: the
page, as the bookmark in its store names it, or today's Month on a first open. Soil finds the
app by the screen that answers `ACTION_OPEN_CALENDAR`, as it finds the Bible's by
`ACTION_OPEN_BIBLE`; the screen is guarded by the seam permission. A link to a day from a
notebook or a document is followed through Soil (`ACTION_FOLLOW` with `EXTRA_CAL_DATE`), and the
calendar opens on that day's Day page over the app that followed it, the bookmark untouched.
Standard launch mode on purpose: Back comes back.

## The store

Everything lives in Soil's app store, `garden/app_<package>.db`, under the global key, re-keyed
and backed up with everything else (`backup.md`). The schema is SN's in two steps (`CalendarSchema`):
`calendar` (one row, minted on the first open), `period`, `page`, `stroke`, `state` (the
bookmark); then `event` with its weekdays, exceptions and reminders, and `note_stroke`. Every
per-calendar table carries a `calendarId`, and every read of a set names one calendar, so the
move to several calendars (`BACKLOG.md`) costs no schema change. The app holds **one lease per
process** (`CalsproutApp.calendar()`, `events()`), handed to every screen and to the render
service alike; a store Soil will not lend is the "Calendar unavailable" dialog, and the screen
leaves.

SN's write rules carry over: rows are minted on the first stroke, never on open, so browsing an
empty year writes nothing but the bookmark; never `INSERT OR REPLACE` on a parent row; strokes
put and dropped by id; no `IN (…)`. What SN did for its binder's 4 MiB cap is gone: a flush is
one transaction of as many statements as strokes, and a page is read in one query. A flush over
the seam's 10,000 statements (`AppStoreLease.MAX_BATCH`), a large paste, goes as several batches in
order, mint first and `updatedAt` last; every statement is idempotent, so a batch that fails is
retried whole. A page a screen minted is written by `(period, half)` rather than by the id it
minted, since two calendar screens (a link opens a second) can each mint one for the same empty
page and the first row wins; a screen reads its page again when it comes back to the front. A store
call that fails because Soil restarted opens the store again and is retried once.

## The screen

The whole skeleton is `:paper`'s `InkScreenActivity`, as the Scratch Pad's is: full-bleed
g-paper, the page-op lock, undo and redo across pages, the debounced save, the chrome band, the
collapsed corner chrome. The grid is the page's **template**, laid out at the page's own size by
`CalendarGeometry` and painted by `CalendarTemplate` with today's ring and the events' marks
baked in; it is re-baked on every navigation and when the date rolls over, and only then (the
bake key). The three layouts are SN arc 33's, full page, the bars floating over it.

The top bar: Back, Pen, Eraser, Lasso, then Month · Week · Day (the one showing latched), Today,
Events. The bottom bar: the pager (‹ title ›, the title opening the shared day picker), and at
its far end **Notes**, present only while something links into the period showing. A finger
double-tap on a Month or Week cell opens that day; in the Notes band, or anywhere on a Day page,
it hides and shows the bars. A finger long-press raises the page sheet: Copy page, Paste page
(while the clipboard holds a page), Export…. The pen is fixed, the pad's: one black pen, the
point and lasso erasers, the lasso. Navigation is `CalendarNavigation`'s anchor rule, SN's.

## Events

SN's arc 24, ported file for file: the event and its small types, the rules and caps, the
recurrence engine (Sunday weeks), the look-ahead that stands in for reminders, the wording, the
three recurring scopes as statement lists, the glyph marks. The Events screen is reached from
the bar and opens on the first day of the period showing: the day's list with Today and
Upcoming, paged against the real band, a trash icon per row with the scope sheet, the calendar's
pager and the day picker on its bottom bar; on the way back it names the day it ended on, and
the calendar follows it in the view it is in and re-bakes. The editor is Greg's three-row
design: the title with its type, the dates with the All day pill and the clock-face times,
Repeat and Remind me as glance buttons over their dialogs; under them the note, one page of
handwriting on a bounded surface or a text field behind one toggle, both kept, the ink riding
the event's own save transaction. Month and Week cells wear the per-type glyphs on the number
row; a Day page's rows carry "N events" or the title.

## Ink across

Copy and paste, never Send (Greg, 2026-10-05), in the notebook's shape exactly (Greg,
2026-10-06): **strokes are the lasso's, pages are the page sheet's.**

- **Strokes.** Copy on the selection bar puts the lasso's strokes on the notebook slot as the
  pad does (`InkClip.envelopeOf`). While the lasso is armed and ink is on the clipboard (the
  lasso wears the mark), a stylus tap on bare paper pastes it centred on the tap, selected; the
  lasso's re-tap popup holds Paste at the source coordinates and Clear clipboard. One undo step.
  The same on the Scratch Pad, which gained a Paste with this effort (`scratchpad.md`).
- **Pages.** Copy page writes the page as a notebook page clip (`InkClip.pageEnvelopeOf`): the
  page row at the page's size, the stroke rows, and a `template` row carrying the grid rendered
  alone (no ring, no marks) as a picture under the image token, so a notebook reuses the row by
  bytes and the same month twice mints one. **A Day writes both halves, AM then PM, as two
  pages.** A notebook's page sheet pastes them before or after the page showing, every page in
  order, on its own paper, one undo step; the Scratch Pad pastes a page as a new page after
  the current one; the calendar's own Paste page lays a copied page's ink on the showing page
  at its own coordinates, since it has no page to insert.

## Export

Export… on the page sheet opens Soil's export screen in its **render-only mode**
(`export.md`): the period showing as PDF or images, the grid toggle the one option, named
`Calendar - September 2026`, `Calendar - Week of 2026-09-06`, `Calendar - 2026-09-08` (a Day's
images `- AM` and `- PM`). The calendar's `RenderService` answers `ACTION_RENDER` for the kind
`calendar` under a `RenderKey` (`M:`, `W:`, `D:` and the period's date), bakes each page as the
screen does, the grid with today's ring and the events' marks under the ink or the ink alone on
white, one page in memory at a time. The calendar stays open behind the screen.

## A day as a link target

`cal:2026-10-06` is an address beside the item's and the Bible's (`links.md`). A notebook makes
one from the picker's fourth shelf, **Calendar day**, on the shared day picker; a document from
**Choose a day…** on the Link dialog, or typed. The link mirror's fifth shape, `PUT_CAL`, carries
the day in `calDate`, indexed at step 11; `calBacklinks(from, to)` answers what links into a
range of days. The calendar's **Notes** door, the Bible reader's Notes in this subject, lists it
for the period showing (a Day its day, a Week its seven, a Month its own days) in the shared
backlinks panel, one row per notebook page or document with the days it names; a tap opens the
source over the calendar.

## Walked on the Nomad

Phases 1, 2, 4 to 9, 2026-10-05 and 2026-10-06; phase 3 was JVM only; phase 10's walk was
skipped (Greg, 2026-10-06), its checks are in the commit.

## Decisions (Greg, 2026-10-05 and 2026-10-06)

- All four parts of SN's calendar come across: pages, events, ink across, export.
- Ink across is copy and paste, never Send. A Day copies both halves as two pages.
- A calendar day is a link target, from a notebook and a document; the calendar lists what links
  to it.
- Events exactly as SN built them.
- The pen is fixed, like the Scratch Pad's: no shades.
- The clipboard in the notebook's shape on every surface: strokes paste by a stylus tap under
  the armed lasso (and the popup), pages by the long-press sheet. No More button.
- The copy icon on every Copy; "Copy page" and "Copy selection", the same words everywhere.
- The Scratch Pad gets a Paste: strokes under the lasso, a page from its long-press sheet.
- The Links door is the Bible reader's Notes: the same icon, the bottom bar's far end.
- The Back arrow stays for now; one leave button for every app is in `BACKLOG.md`.

Proposed by the build and standing until Greg says otherwise: `ACTION_OPEN_CALENDAR` and
`EXTRA_CAL_DATE`; one store lease per process and a `calendarId` on every table; the dates, the
picker, the lasso popup, the placement rule and the backlinks panel in `:paper`; the export
screen's render-only mode with the key as the renderer's item id; `PUT_CAL` with one column;
a Month's Notes scope as its own days, not the grid's spare cells; the grid as a lossy WEBP
template, SN's rule; the paper toggle taking the ring and the marks with the grid.

## Traps

A store Soil cached across an app update was not migrated to the schema's new step
(`AppStores.open` now migrates on the cached path). A notebook read the clipboard's header once
per process, so a second notebook never saw what the calendar had copied: it reads it at every
open. `adb shell am force-stop` of Soil drops its accessibility service and with it the side
menu; the way back is in `building.md`. Over adb, the Supernote's file picker answers a tap on
its Save button though not on its rows; a long-press is `input swipe x y x y 1200`.
