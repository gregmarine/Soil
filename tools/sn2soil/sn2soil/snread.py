"""Reading a Notesprout SN library: the index `notesprout.db`, a notebook `.soil`, and the
extension stores `Garden/<pkg>.db`. Every connection here is to a *plaintext* copy the CLI made
in its work folder (or the file itself under `--plain`). Facts from the SN source, 2026-10-11:
`ObjectEntity.kt`, `SoilSchema.kt`, `LinkPayload.kt`, the extensions' `*Schema.kt`."""

from __future__ import annotations

import sqlite3
from dataclasses import dataclass, field

PKG_HOST = "com.symmetricalpalmtree.notesproutsn"
EXT_CALENDAR = PKG_HOST + ".ext.calendar"
EXT_SCRATCHPAD = PKG_HOST + ".ext.scratchpad"
EXT_TAGS = PKG_HOST + ".ext.tags"
EXT_BIBLE = PKG_HOST + ".ext.bible"
EXT_DOCUMENT = PKG_HOST + ".ext.document"
EXT_CLOUD = PKG_HOST + ".ext.cloud"

# Sentinel ids in the index (ListIds.kt).
PINNED_LIST = "00000000-0000-0000-0000-70696e6e6564"
TEMPLATE_PINNED_LIST = "00000000-0000-0000-0000-7470696e6e64"
BUILTIN_TEMPLATES = {
    "00000000-0000-0000-0000-5f626c616e6b": "BLANK",
    "00000000-0000-0000-0000-6465666c745f": "DEFAULT",
    "00000000-0000-0000-0000-6c696e65645f": "LINED",
    "00000000-0000-0000-0000-646f74746564": "DOTTED",
    "00000000-0000-0000-0000-5f677269645f": "GRID",
}

# Notebook flags (ObjectEntity.kt).
FLAG_ENCRYPTED = 1
FLAG_EXCLUDE_FROM_BACKUP = 2
FLAG_TEXT_DOCUMENT = 4
FLAG_SKETCH = 8

Row = dict


def table_exists(con: sqlite3.Connection, name: str) -> bool:
    return con.execute("SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?", (name,)).fetchone()[0] > 0


def load_table(con: sqlite3.Connection, table: str) -> list[Row]:
    con.row_factory = sqlite3.Row
    try:
        return [dict(r) for r in con.execute(f'SELECT * FROM "{table}"')]
    finally:
        con.row_factory = None


# ── the index ──────────────────────────────────────────────────────────────


@dataclass
class SnNotebook:
    id: str
    name: str
    parent_id: str | None
    flags: int
    key_scope: str
    page_count: int | None
    cover: bytes | None
    created_at: int
    updated_at: int
    deleted_at: int | None

    @property
    def text_document(self) -> bool:
        return bool(self.flags & FLAG_TEXT_DOCUMENT)

    @property
    def sketch(self) -> bool:
        # TEXT wins when both bits are set (NotebookKind.kt).
        return bool(self.flags & FLAG_SKETCH) and not self.text_document

    @property
    def excluded_from_backup(self) -> bool:
        return bool(self.flags & FLAG_EXCLUDE_FROM_BACKUP)


@dataclass
class SnIndex:
    folders: list[Row] = field(default_factory=list)          # alive `folder` rows
    notebooks: list[SnNotebook] = field(default_factory=list)  # every `notebook` row, trash included
    naming: list[Row] = field(default_factory=list)           # alive `naming` rows (parentId = folder or None)
    template_folders: list[Row] = field(default_factory=list)
    templates: list[Row] = field(default_factory=list)        # alive library `template` rows
    pinned: list[str] = field(default_factory=list)           # member ids in sortOrder
    pinned_templates: list[str] = field(default_factory=list)
    skipped_types: dict[str, int] = field(default_factory=dict)

    def alive_folder_ids(self) -> set[str]:
        return {f["id"] for f in self.folders}


def read_index(con: sqlite3.Connection) -> SnIndex:
    rows = load_table(con, "objects")
    idx = SnIndex()
    items_by_list: dict[str, list[tuple[int, str]]] = {}
    for r in rows:
        t = r["type"]
        alive = r.get("deletedAt") is None
        if t == "folder":
            if alive:
                idx.folders.append(r)
        elif t == "notebook":
            idx.notebooks.append(SnNotebook(
                id=r["id"], name=r["name"], parent_id=r.get("parentId"), flags=int(r.get("flags") or 0),
                key_scope=(r.get("keyScope") or "GLOBAL"), page_count=r.get("pageCount"), cover=r.get("blob"),
                created_at=int(r["createdAt"]), updated_at=int(r["updatedAt"]), deleted_at=r.get("deletedAt"),
            ))
        elif t == "naming":
            if alive:
                idx.naming.append(r)
        elif t == "template_folder":
            if alive:
                idx.template_folders.append(r)
        elif t == "template":
            if alive:
                idx.templates.append(r)
        elif t == "list_item":
            if alive and r.get("refId"):
                items_by_list.setdefault(r.get("parentId") or "", []).append((int(r.get("sortOrder") or 0), r["refId"]))
        elif t == "list":
            pass
        else:
            # backup, clipboard, export_preset: left behind on purpose.
            idx.skipped_types[t] = idx.skipped_types.get(t, 0) + 1
    idx.pinned = [m for _, m in sorted(items_by_list.get(PINNED_LIST, []))]
    idx.pinned_templates = [m for _, m in sorted(items_by_list.get(TEMPLATE_PINNED_LIST, []))]
    return idx


def resolve_parents(idx: SnIndex) -> None:
    """A folder whose parent is gone sits at the root; a notebook in a gone folder too."""
    alive = idx.alive_folder_ids()
    for f in idx.folders:
        if f.get("parentId") and f["parentId"] not in alive:
            f["parentId"] = None
    for n in idx.notebooks:
        if n.parent_id and n.parent_id not in alive:
            n.parent_id = None
    tf_alive = {f["id"] for f in idx.template_folders}
    for f in idx.template_folders:
        if f.get("parentId") and f["parentId"] not in tf_alive:
            f["parentId"] = None
    for t in idx.templates:
        if t.get("parentId") and t["parentId"] not in tf_alive:
            t["parentId"] = None


# ── a notebook file ────────────────────────────────────────────────────────

SOIL_COLUMNS = ["id", "parentId", "type", "order", "createdAt", "updatedAt", "deletedAt", "text", "refId",
                "x", "y", "width", "height", "color", "strokeWidth", "style", "flags", "blob"]

# Rows that are the notebook's own ink and objects (Soil's notebook reads these).
NOTEBOOK_CONTENT_TYPES = {"stroke", "heading", "text", "link", "sticky_note", "shape"}
# Rows that leave the notebook for another kind of item.
SKETCH_TYPES = {"sketch_graphite", "sketch_ink", "guide_grid", "guide_image"}
DEAD_TYPES = {"sketch", "page_text", "layer", "object"}


class Tree:
    """Rows by id and by parent, in `order`."""

    def __init__(self, rows: list[Row]):
        self.by_id: dict[str, Row] = {r["id"]: r for r in rows}
        self.kids: dict[str, list[Row]] = {}
        for r in rows:
            self.kids.setdefault(r["parentId"], []).append(r)
        for lst in self.kids.values():
            lst.sort(key=lambda r: (int(r.get("order") or 0), int(r.get("createdAt") or 0)))

    def children(self, pid: str) -> list[Row]:
        return self.kids.get(pid, [])

    def descendants(self, pid: str) -> list[Row]:
        out: list[Row] = []
        stack = list(self.children(pid))
        while stack:
            r = stack.pop(0)
            out.append(r)
            stack = self.children(r["id"]) + stack
        return out


@dataclass
class SnSoil:
    notebook_id: str
    root: Row | None
    tree: Tree
    meta: dict
    pages: list[Row]  # alive pages in order

    @property
    def title(self) -> str | None:
        return self.root.get("text") if self.root else None

    @property
    def last_page(self) -> str | None:
        return self.root.get("refId") if self.root else None


def read_soil(con: sqlite3.Connection, notebook_id: str) -> SnSoil:
    import json

    rows = load_table(con, "notebook")
    tree = Tree(rows)
    root = tree.by_id.get(notebook_id)
    if root is None or root.get("type") != "notebook":
        roots = [r for r in tree.children("") if r.get("type") == "notebook"]
        root = roots[0] if roots else None
    meta: dict = {}
    if table_exists(con, "notebook_meta"):
        row = con.execute("SELECT json FROM notebook_meta WHERE id = 0").fetchone()
        if row and row[0]:
            try:
                meta = json.loads(row[0])
            except ValueError:
                meta = {}
    root_id = root["id"] if root else notebook_id
    pages = [p for p in tree.children(root_id) if p.get("type") == "page" and p.get("deletedAt") is None]
    return SnSoil(notebook_id=notebook_id, root=root, tree=tree, meta=meta, pages=pages)


def page_documents(soil: SnSoil) -> list[tuple[Row, str]]:
    """(page row, markdown) for every alive page with a live, non-blank document, in page order."""
    out = []
    for p in soil.pages:
        docs = [d for d in soil.tree.children(p["id"]) if d.get("type") == "document" and d.get("deletedAt") is None and (d.get("text") or "").strip()]
        if docs:
            docs.sort(key=lambda d: int(d.get("updatedAt") or 0))
            out.append((p, str(docs[-1]["text"])))
    return out


def notebook_document(soil: SnSoil) -> str | None:
    root_id = soil.root["id"] if soil.root else soil.notebook_id
    docs = [d for d in soil.tree.children(root_id) if d.get("type") == "document" and d.get("deletedAt") is None and (d.get("text") or "").strip()]
    if not docs:
        return None
    docs.sort(key=lambda d: int(d.get("updatedAt") or 0))
    return str(docs[-1]["text"])


# ── link payloads ──────────────────────────────────────────────────────────

LINK_PAGE, LINK_NOTEBOOK, LINK_NOTEBOOK_PAGE, LINK_BIBLE, LINK_BIBLE_TEXT = 0, 1, 2, 3, 4


@dataclass
class LinkTarget:
    kind: int
    chrome: int
    notebook_id: str
    page_id: str

    @property
    def wire(self) -> str:
        return self.notebook_id if self.kind in (LINK_BIBLE, LINK_BIBLE_TEXT) else ""


def parse_link(payload: str | None) -> LinkTarget | None:
    if not payload:
        return None
    parts = payload.split("|")
    if len(parts) != 5 or parts[0] != "L1":
        return None
    try:
        chrome, kind = int(parts[1]), int(parts[2])
    except ValueError:
        return None
    if kind not in range(5):
        return None
    return LinkTarget(kind=kind, chrome=chrome, notebook_id=parts[3], page_id=parts[4])
