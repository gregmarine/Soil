"""`soil.db`: folders, items, pins, naming schemes, the paper library, tags, pages."""

from __future__ import annotations

import re
import sqlite3
from pathlib import Path

from . import soilschema as S
from .items import Ctx, ItemOut
from .snread import BUILTIN_TEMPLATES, SnIndex

SCHEME_TOKEN = re.compile(r"\{([a-z]+)(?::(\d))?\}")
SCHEME_TOKENS = {"date", "time", "year", "month", "day", "monthname", "weekday", "mon", "wd", "n"}
UUID_RE = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")


def clean_name(name: str | None, fallback: str) -> str:
    s = " ".join((name or "").split())
    return (s or fallback)[:200]


def scheme_ok(scheme: str) -> bool:
    if not scheme or len(scheme) > 100:
        return False
    counters = 0
    for m in SCHEME_TOKEN.finditer(scheme):
        if m.group(1) not in SCHEME_TOKENS:
            return False
        if m.group(1) == "n":
            counters += 1
    return counters <= 1


def write_index(path: Path, sn: SnIndex, items: list[ItemOut], tags: tuple[list, list] | None, ctx: Ctx) -> None:
    con = S.create_plain(path, S.INDEX_DDL, S.INDEX_USER_VERSION)

    # Folders
    for f in sn.folders:
        con.execute("INSERT INTO folder (id, parentId, name, createdAt, updatedAt, deletedAt) VALUES (?, ?, ?, ?, ?, NULL)",
                    (f["id"], f.get("parentId") or "", clean_name(f["name"], "Folder"), int(f["createdAt"]), int(f["updatedAt"])))
    ctx.bump("folders", len(sn.folders))

    # Items and their pages
    for it in items:
        con.execute(
            "INSERT INTO item (id, kind, name, keyScope, flags, createdAt, updatedAt, deletedAt, pageCount, openedAt, parentId, cover) "
            "VALUES (?, ?, ?, 'GLOBAL', ?, ?, ?, NULL, ?, NULL, ?, ?)",
            (it.id, it.kind, clean_name(it.name, it.kind.capitalize()), it.flags, it.created_at, it.updated_at,
             len(it.page_ids), it.parent_id or "", it.cover),
        )
        con.executemany("INSERT INTO item_page (itemId, pageId, position) VALUES (?, ?, ?)",
                        [(it.id, pid, n) for n, pid in enumerate(it.page_ids)])

    # Pins: a pinned SN notebook is the item that kept its id
    item_ids = {it.id for it in items}
    for n, member in enumerate(sn.pinned):
        if member in item_ids:
            con.execute("INSERT OR IGNORE INTO item_pin (id, pinnedAt) VALUES (?, ?)", (member, n))
            ctx.bump("pins")
        else:
            ctx.warn(f"pin to {member}: no such item any more; dropped")

    # Naming schemes → folder_prefs
    for r in sn.naming:
        scheme = (r.get("name") or "").strip()
        folder = r.get("parentId") or ""
        if not scheme_ok(scheme):
            ctx.warn(f"naming scheme {scheme!r} on folder {folder or 'root'}: not in Soil's grammar; dropped")
            continue
        con.execute("INSERT OR REPLACE INTO folder_prefs (folderId, scheme, template) VALUES (?, ?, NULL)", (folder, scheme))
        ctx.bump("naming schemes")

    # The paper library
    for f in sn.template_folders:
        con.execute("INSERT INTO template_folder (id, parentId, name, createdAt, updatedAt, deletedAt) VALUES (?, ?, ?, ?, ?, NULL)",
                    (f["id"], f.get("parentId") or "", clean_name(f["name"], "Folder"), int(f["createdAt"]), int(f["updatedAt"])))
    ctx.bump("template folders", len(sn.template_folders))
    kept: set[str] = set()
    for t in sn.templates:
        kind = t.get("templateKind") or "IMAGE"
        if kind != "IMAGE" or not t.get("blob"):
            ctx.warn(f"library template {t.get('name')!r} is {kind} with {'an' if t.get('blob') else 'no'} image; Soil's library holds pictures only; dropped")
            continue
        fit = int(t.get("flags") or 0)
        con.execute("INSERT INTO template (id, parentId, name, fit, createdAt, updatedAt, deletedAt, blob) VALUES (?, ?, ?, ?, ?, ?, NULL, ?)",
                    (t["id"], t.get("parentId") or "", clean_name(t["name"], "Paper"), fit if fit in (0, 1, 2) else 0,
                     int(t["createdAt"]), int(t["updatedAt"]), t["blob"]))
        kept.add(t["id"])
        ctx.bump("library templates")
    for n, member in enumerate(sn.pinned_templates):
        if member in kept:
            con.execute("INSERT OR IGNORE INTO template_pin (id, pinnedAt) VALUES (?, ?)", (member, n))
        elif member in BUILTIN_TEMPLATES:
            pass  # Soil's built-in papers are not rows and are not pinned
        else:
            ctx.warn(f"template pin to {member}: no such template; dropped")

    # Tags
    if tags:
        tag_rows, assigns = tags
        primary_pages: dict[str, set[str]] = {it.id: set(it.page_ids) for it in items if it.primary}
        kept_tags: set[str] = set()
        for t in tag_rows:
            display = " ".join((t.get("display") or "").split())[:64]
            if not display or not UUID_RE.match(t["id"]):
                ctx.warn(f"tag {t.get('id')}: no name or not a UUID; dropped")
                continue
            try:
                con.execute("INSERT INTO tag (id, display, identityKey, createdAt) VALUES (?, ?, ?, ?)",
                            (t["id"], display, display.lower(), int(t.get("createdAt") or 0)))
            except sqlite3.IntegrityError:
                ctx.warn(f"tag {display!r}: a second tag with the same name; dropped")
                continue
            kept_tags.add(t["id"])
        for a in assigns:
            if a["tagId"] not in kept_tags or a["notebookId"] not in primary_pages:
                ctx.warn(f"tag assignment on {a.get('notebookId')}: its tag or its item is gone; dropped")
                continue
            page = a.get("pageId") or ""
            if page and page not in primary_pages[a["notebookId"]]:
                ctx.warn(f"a page tag on {a['notebookId']}: the page is not in the item that kept the id; made an item tag")
                page = ""
            con.execute("INSERT OR IGNORE INTO tag_assignment (tagId, itemId, pageId, createdAt) VALUES (?, ?, ?, ?)",
                        (a["tagId"], a["notebookId"], page, int(a.get("createdAt") or 0)))
            ctx.bump("tag assignments")
        ctx.bump("tags", len(kept_tags))

    con.commit()
    con.close()
