"""One SN notebook file → its Soil items. Every SN notebook has up to three parts: its paper
(strokes and objects on pages), its sketch (rasters and guides on pages) and its words (page
documents and the notebook document). The part the SN kind names is the **primary** item and keeps
the SN notebook's id, so every link to it and every tag on it still resolves; each other part that
is not empty becomes an item **beside** it, in the same folder, under the same name, with a fresh
id (Greg, 2026-10-11: a Document beside a notebook; a notebook beside a sketchbook)."""

from __future__ import annotations

import math
import sqlite3
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from . import soilschema as S
from .snread import (LINK_BIBLE, LINK_BIBLE_TEXT, LINK_NOTEBOOK, LINK_NOTEBOOK_PAGE, LINK_PAGE, NOTEBOOK_CONTENT_TYPES,
                     SKETCH_TYPES, Row, SnNotebook, SnSoil, notebook_document, page_documents, parse_link)

Warn = Callable[[str], None]
COVER_MAX = 1024 * 1024


@dataclass
class ItemOut:
    """One Soil item the converter wrote, for the index."""
    id: str
    kind: str
    name: str
    parent_id: str | None
    flags: int
    created_at: int
    updated_at: int
    page_ids: list[str]
    cover: bytes | None
    plain_path: Path          # the plaintext file in the work folder
    primary: bool             # keeps the SN id
    counts: dict[str, int] = field(default_factory=dict)


@dataclass
class Ctx:
    warn: Warn
    counts: dict[str, int] = field(default_factory=dict)

    def bump(self, key: str, n: int = 1) -> None:
        self.counts[key] = self.counts.get(key, 0) + n


def _finite(*vals) -> bool:
    return all(v is not None and math.isfinite(float(v)) for v in vals)


def _row_tuple(r: Row, parent: str, order: int | None = None, text=None, refid=None) -> tuple:
    return (
        r["id"], parent, r["type"], int(r.get("order") or 0) if order is None else order,
        int(r.get("createdAt") or 0), int(r.get("updatedAt") or r.get("createdAt") or 0), None,
        r.get("text") if text is None else text, r.get("refId") if refid is None else refid,
        r.get("x"), r.get("y"), r.get("width"), r.get("height"),
        r.get("color"), r.get("strokeWidth"), r.get("style"), r.get("flags"), r.get("blob"),
    )


def _alive(rows: list[Row]) -> list[Row]:
    return [r for r in rows if r.get("deletedAt") is None]


class Parts:
    """What an SN file holds, split three ways, alive rows only."""

    def __init__(self, soil: SnSoil, ctx: Ctx):
        self.soil = soil
        self.ctx = ctx
        t = soil.tree
        root_id = soil.root["id"] if soil.root else soil.notebook_id
        self.templates: list[Row] = [r for r in _alive(t.children(root_id)) if r.get("type") == "template" and r.get("blob")]
        self.pages: list[Row] = soil.pages
        # paper: content rows on each page, with every descendant (a link's or a sticky's children)
        self.paper: dict[str, list[Row]] = {}
        self.sketch: dict[str, list[Row]] = {}
        self.unknown: dict[str, int] = {}
        for p in self.pages:
            paper: list[Row] = []
            sketch: list[Row] = []
            for r in _alive(t.children(p["id"])):
                ty = r.get("type")
                if ty in NOTEBOOK_CONTENT_TYPES:
                    paper.append(r)
                    paper.extend(_alive(t.descendants(r["id"])))
                elif ty in SKETCH_TYPES:
                    sketch.append(r)
                elif ty == "document":
                    pass
                else:
                    self.unknown[ty] = self.unknown.get(ty, 0) + 1
            if paper:
                self.paper[p["id"]] = paper
            if sketch:
                self.sketch[p["id"]] = sketch
        self.page_docs = page_documents(soil)
        self.nb_doc = notebook_document(soil)

    @property
    def has_paper(self) -> bool:
        return bool(self.paper)

    @property
    def has_sketch(self) -> bool:
        return bool(self.sketch)

    @property
    def words(self) -> str | None:
        if self.nb_doc:
            return self.nb_doc
        if self.page_docs:
            return "\n\n".join(d.strip() for _, d in self.page_docs)
        return None


def convert(soil: SnSoil, sn: SnNotebook, work: Path, ctx: Ctx) -> list[ItemOut]:
    parts = Parts(soil, ctx)
    label = f"{sn.name!r} ({sn.id})"
    for ty, n in parts.unknown.items():
        ctx.warn(f"{label}: {n} row(s) of type {ty!r} left behind")
    name = sn.name
    flags = S.ITEM_FLAG_EXCLUDE_FROM_BACKUP if sn.excluded_from_backup else 0
    cover = sn.cover if sn.cover and len(sn.cover) <= COVER_MAX else None
    out: list[ItemOut] = []

    if sn.text_document:
        primary = "words"
    elif sn.sketch:
        primary = "sketch"
    else:
        primary = "paper"

    def item_id(part: str) -> str:
        return sn.id if part == primary else str(uuid.uuid4())

    # Words
    words = parts.words
    if primary == "words" or words:
        did = item_id("words")
        if primary == "words" and not words:
            words = ""
            ctx.warn(f"{label}: a text document with no text; an empty Document")
        out.append(_write_document(did, name, sn, parts, words or "", flags, work, ctx, primary == "words"))
        if primary != "words":
            ctx.bump("documents beside")

    # Paper
    if primary == "paper" or parts.has_paper:
        pid = item_id("paper")
        out.append(_write_paper(pid, "notebook", S.NOTEBOOK_DDL, name, sn, parts, flags, cover if primary == "paper" else None,
                                work, ctx, primary == "paper", fresh_pages=(primary != "paper")))
        if primary != "paper":
            ctx.bump("notebooks beside")

    # Sketch
    if primary == "sketch" or parts.has_sketch:
        sid = item_id("sketch")
        out.append(_write_paper(sid, "sketchbook", S.SKETCHBOOK_DDL, name, sn, parts, flags, cover if primary == "sketch" else None,
                                work, ctx, primary == "sketch", fresh_pages=False))
        if primary != "sketch":
            ctx.bump("sketchbooks beside")
            ctx.warn(f"{label}: a handwritten notebook carrying sketch rasters; they became a sketchbook beside it")
    return out


def _write_document(did: str, name: str, sn: SnNotebook, parts: Parts, text: str, flags: int, work: Path, ctx: Ctx, primary: bool) -> ItemOut:
    data = text.encode("utf-8")
    if len(data) > S.DOCUMENT_MAX_BYTES:
        ctx.warn(f"{name!r}: document text is {len(data)} bytes, over Soil's 5 MiB; cut at the limit")
        text = data[: S.DOCUMENT_MAX_BYTES].decode("utf-8", errors="ignore")
    path = work / f"{did}.document.out"
    con = S.create_plain(path, S.DOCUMENT_DDL, S.ITEM_USER_VERSION)
    S.write_item_tables(con, did, S.KIND_DOCUMENT, name, sn.created_at)
    con.execute("INSERT INTO document (id, parentId, type, createdAt, updatedAt, deletedAt, text, refId) VALUES (?, '', 'document', ?, ?, NULL, NULL, NULL)",
                (did, sn.created_at, sn.updated_at))
    con.execute("INSERT INTO document (id, parentId, type, createdAt, updatedAt, deletedAt, text, refId) VALUES (?, ?, 'body', ?, ?, NULL, ?, NULL)",
                (str(uuid.uuid4()), did, sn.created_at, sn.updated_at, text))
    con.commit()
    con.close()
    ctx.bump("documents")
    src = "the notebook document" if parts.nb_doc else f"{len(parts.page_docs)} page document(s)"
    return ItemOut(did, S.KIND_DOCUMENT, name, sn.parent_id, flags, sn.created_at, sn.updated_at, [], None, path, primary,
                   counts={"chars": len(text), "from": src})


def _write_paper(iid: str, table: str, ddl: list[str], name: str, sn: SnNotebook, parts: Parts, flags: int, cover: bytes | None,
                 work: Path, ctx: Ctx, primary: bool, fresh_pages: bool) -> ItemOut:
    """A notebook or a sketchbook file: root, templates, pages, and that part's rows."""
    kind = S.KIND_NOTEBOOK if table == "notebook" else S.KIND_SKETCHBOOK
    path = work / f"{iid}.{kind}.out"
    con = S.create_plain(path, ddl, S.ITEM_USER_VERSION)
    S.write_item_tables(con, iid, kind, name, sn.created_at)
    ins = S.INSERT_ROW.format(table=table)
    rows: list[tuple] = []
    counts: dict[str, int] = {}

    def bump(k: str, n: int = 1) -> None:
        counts[k] = counts.get(k, 0) + n

    # Templates
    template_ids: set[str] = set()
    for t in parts.templates:
        template_ids.add(t["id"])
        rows.append(_row_tuple(t, iid, order=0))
        bump("templates")

    # Pages: dense order, alive only; fresh ids for a notebook beside a sketchbook
    page_map: dict[str, str] = {}
    page_ids: list[str] = []
    for n, p in enumerate(parts.pages):
        w, h = p.get("width"), p.get("height")
        if not _finite(w, h) or float(w) <= 0 or float(h) <= 0:
            ctx.warn(f"{name!r}: page {p['id']} has no size; left out")
            continue
        new_id = str(uuid.uuid4()) if fresh_pages else p["id"]
        page_map[p["id"]] = new_id
        page_ids.append(new_id)
        tid = p.get("refId") or ""
        if tid and tid not in template_ids:
            tid = ""
        pr = dict(p)
        pr["id"] = new_id
        rows.append(_row_tuple(pr, iid, order=len(page_ids) - 1, refid=tid))
    if not page_ids:
        now = sn.updated_at
        pid = str(uuid.uuid4())
        w, h = _default_size(parts)
        rows.append((pid, iid, "page", 0, now, now, None, None, "", None, None, w, h, None, None, None, None, None))
        page_ids.append(pid)
        ctx.warn(f"{name!r}: no live page; one blank page made")
    bump("pages", len(page_ids))

    # Root
    last = parts.soil.last_page
    last = page_map.get(last, None) if last else None
    if last not in page_ids:
        last = page_ids[0]
    title = parts.soil.title or name
    rows.insert(0, (iid, "", table, 0, sn.created_at, sn.updated_at, None, title, last, None, None, None, None, None, None, None, None, None))

    # Content
    links: list[tuple] = []
    if table == "notebook":
        for old_pid, prs in parts.paper.items():
            new_pid = page_map.get(old_pid)
            if new_pid is None:
                continue
            for r in prs:
                ty = r["type"]
                parent = new_pid if r["parentId"] == old_pid else r["parentId"]
                text = r.get("text")
                if ty in ("heading", "text") and not (text or "").strip():
                    ctx.warn(f"{name!r}: a {ty} with no words on page {old_pid}; left out")
                    continue
                if ty == "link":
                    if not _finite(r.get("x"), r.get("y"), r.get("width"), r.get("height")):
                        ctx.warn(f"{name!r}: a link with no bounds on page {old_pid}; left out")
                        continue
                    target = parse_link(text)
                    if target is None:
                        ctx.warn(f"{name!r}: a link whose payload could not be read; carried as is")
                    elif target.kind == LINK_PAGE and fresh_pages:
                        text = f"L1|{target.chrome}|0||{page_map.get(target.page_id, target.page_id)}"
                        target.page_id = page_map.get(target.page_id, target.page_id)
                    if target is not None:
                        links.extend(_mirror_rows(r["id"], new_pid, iid, target))
                if ty == "shape":
                    bump("shapes")
                rows.append(_row_tuple(r, parent, text=text))
                bump(ty if ty != "shape" else "shape rows")
    else:
        for old_pid, srs in parts.sketch.items():
            new_pid = page_map.get(old_pid)
            if new_pid is None:
                continue
            for r in srs:
                rows.append(_row_tuple(r, new_pid, order=-1))
                bump(r["type"])

    con.executemany(ins, rows)
    if links:
        con.executemany(
            "INSERT OR REPLACE INTO soil_link (id, pageId, targetItemId, targetPageId, bibleWire, bibleStart, bibleEnd, calDate) VALUES (?, ?, ?, ?, ?, ?, ?, NULL)",
            links,
        )
    con.commit()
    con.close()
    ctx.bump(kind + "s")
    for k, v in counts.items():
        ctx.bump(k, v)
    return ItemOut(iid, kind, name, sn.parent_id, flags, sn.created_at, sn.updated_at, page_ids, cover, path, primary, counts)


def _default_size(parts: Parts) -> tuple[float, float]:
    for p in parts.pages:
        if _finite(p.get("width"), p.get("height")):
            return float(p["width"]), float(p["height"])
    return 1920.0, 2560.0


def _mirror_rows(link_id: str, page_id: str, own_item: str, t) -> list[tuple]:
    """`soil_link` rows for one link row (Soil's `NotebookSql.mirrorRows`)."""
    if t.kind == LINK_PAGE:
        return [(link_id, page_id, own_item, t.page_id, None, None, None)]
    if t.kind == LINK_NOTEBOOK:
        return [(link_id, page_id, t.notebook_id, None, None, None, None)]
    if t.kind == LINK_NOTEBOOK_PAGE:
        return [(link_id, page_id, t.notebook_id, t.page_id or None, None, None, None)]
    if t.kind in (LINK_BIBLE, LINK_BIBLE_TEXT):
        ranges = S.wire_ranges(t.wire)
        if not ranges:
            return []
        return [(S.range_id(link_id, n), page_id, "", None, t.wire, s, e) for n, (s, e) in enumerate(ranges)]
    return []
