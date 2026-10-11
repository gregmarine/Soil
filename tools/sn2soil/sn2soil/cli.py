"""sn2soil — command line.

  python3 sn2soil.py --in <SN library folder> --out <Soil backup folder> [--passphrase-file F]
                     [--out-passphrase-file F] [--in-variant release|dev] [--out-variant release|dev]
                     [--keep-work] [--plain]

The input folder holds `notesprout.db` and `Garden/` (`<uuid>.soil` files and `<pkg>.db` stores),
as pulled off the device or as SN's local backup wrote it; `-wal` sidecars beside a file are
honoured. The output folder is a Soil backup set: `soil.db`, one `<id>.soil` per item, the
Scratch Pad's, the calendar's and the Bible's stores, all encrypted under the output passphrase,
plus `report.txt`. Soil's Restore installs it whole.

`--plain` reads plaintext inputs and writes plaintext outputs (tests and inspection only).
"""

from __future__ import annotations

import argparse
import datetime as dt
import getpass
import shutil
import sqlite3
import sys
from pathlib import Path

from . import cipher, snread
from . import soilschema as S
from .index import write_index
from .items import Ctx, convert
from .stores import build_bible, build_calendar, build_scratch, count_words, read_tags


def _read_secret(path: str | None, prompt: str) -> str:
    if path:
        return Path(path).read_text(encoding="utf-8").rstrip("\r\n")
    if not sys.stdin.isatty():
        raise SystemExit(f"{prompt}: no TTY — pass --passphrase-file")
    return getpass.getpass(prompt + ": ")


class Report:
    def __init__(self) -> None:
        self.lines: list[str] = []
        self.warnings: list[str] = []

    def log(self, s: str) -> None:
        self.lines.append(s)
        print(s)

    def warn(self, s: str) -> None:
        self.warnings.append(s)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="sn2soil", description="Convert a Notesprout SN library into a Soil backup set.")
    ap.add_argument("--in", dest="src", required=True, help="SN library folder (notesprout.db + Garden/)")
    ap.add_argument("--out", dest="dst", required=True, help="output folder (created; must be empty)")
    ap.add_argument("--passphrase-file", help="file holding the SN global passphrase (else prompted)")
    ap.add_argument("--out-passphrase-file", help="file holding the passphrase for the Soil set (default: the same)")
    ap.add_argument("--in-variant", choices=("release", "dev"), default="release", help="SN build the library is from (store file names)")
    ap.add_argument("--out-variant", choices=("release", "dev"), default="release", help="Soil build the set is for (store file names)")
    ap.add_argument("--keep-work", action="store_true", help="keep the plaintext work folder (inspection only)")
    ap.add_argument("--plain", action="store_true", help="plaintext in, plaintext out (tests)")
    args = ap.parse_args(argv)

    src = Path(args.src).expanduser().resolve()
    dst = Path(args.dst).expanduser().resolve()
    if not src.is_dir():
        raise SystemExit(f"--in: not a folder: {src}")
    if dst.exists() and any(dst.iterdir()):
        raise SystemExit(f"--out: folder is not empty: {dst}")
    dst.mkdir(parents=True, exist_ok=True)
    work = dst / "_work"
    work.mkdir()

    rep = Report()
    ctx = Ctx(warn=rep.warn)
    in_pass = out_pass = ""
    if not args.plain:
        in_pass = _read_secret(args.passphrase_file, "SN global passphrase")
        out_pass = _read_secret(args.out_passphrase_file, "Soil passphrase") if args.out_passphrase_file else in_pass
        if not in_pass:
            raise SystemExit("empty passphrase")

    def plain_of(path: Path) -> Path:
        if args.plain:
            return path
        out = work / (path.name + ".plain")
        if not cipher.is_encrypted(path):
            raise SystemExit(f"{path.name} is not encrypted: not an SN file, or the wrong folder")
        cipher.decrypt_to_plain(path, in_pass, out)
        return out

    def emit(plain: Path, name: str, user_version: int) -> None:
        if args.plain:
            shutil.move(str(plain), str(dst / name))
            return
        cipher.encrypt_from_plain(plain, out_pass, dst / name, user_version)
        cipher.verify(dst / name, out_pass, user_version)

    garden = src / "Garden" if (src / "Garden").is_dir() else src
    in_suffix = ".dev" if args.in_variant == "dev" else ""

    def store_path(pkg: str) -> Path | None:
        p = garden / f"{pkg}{in_suffix}.db"
        return p if p.exists() else None

    # ── the SN index ──
    index_file = src / "notesprout.db"
    if not index_file.exists():
        raise SystemExit(f"no notesprout.db in {src}")
    rep.log(f"Reading SN index {index_file}")
    sn_index_con = sqlite3.connect(plain_of(index_file))
    if not snread.table_exists(sn_index_con, "objects"):
        raise SystemExit("notesprout.db has no objects table — wrong passphrase or not an SN index")
    sn = snread.read_index(sn_index_con)
    snread.resolve_parents(sn)
    sn_index_con.close()
    alive = [n for n in sn.notebooks if n.deleted_at is None]
    rep.log(f"  {len(alive)} notebooks ({len(sn.notebooks) - len(alive)} in the trash, left out), {len(sn.folders)} folders, "
            f"{len(sn.templates)} library templates, {len(sn.pinned)} pins")
    for t, n in sorted(sn.skipped_types.items()):
        rep.log(f"  · {n} {t} row(s) in the index left behind")

    # ── notebooks ──
    items = []
    for n in alive:
        label = f"{n.name!r} ({n.id})"
        f = garden / f"{n.id}.soil"
        if not f.exists():
            rep.warn(f"{label}: file missing, left out")
            continue
        if n.key_scope != "GLOBAL":
            rep.warn(f"{label}: NOTEBOOK-scope passphrase — refused, left out")
            continue
        try:
            con = sqlite3.connect(plain_of(f))
            if not snread.table_exists(con, "notebook"):
                raise cipher.CipherError("not a notebook file")
            soil = snread.read_soil(con, n.id)
        except cipher.CipherError as e:
            rep.warn(f"{label}: could not open ({e}); left out")
            continue
        con.close()
        outs = convert(soil, n, work, ctx)
        for it in outs:
            emit(it.plain_path, f"{it.id}.soil", S.ITEM_USER_VERSION)
            items.append(it)
        kind = "text document" if n.text_document else "sketch notebook" if n.sketch else "notebook"
        made = ", ".join(f"{it.kind}" + ("" if it.primary else " beside") + _summary(it) for it in outs)
        rep.log(f"  ✓ {n.name!r} ({kind}) → {made}")

    # ── stores ──
    now = int(dt.datetime.now().timestamp() * 1000)
    tags = None
    p = store_path(snread.EXT_TAGS)
    if p:
        con = sqlite3.connect(plain_of(p))
        tags = read_tags(con)
        con.close()
    else:
        rep.log("  · no tags store")

    p = store_path(snread.EXT_SCRATCHPAD)
    out = work / "scratch.out"
    con = sqlite3.connect(plain_of(p)) if p else sqlite3.connect(":memory:")
    build_scratch(con, out, ctx)
    con.close()
    emit(out, S.STORE_SCRATCHPAD, S.SCRATCH_USER_VERSION)
    rep.log(f"  scratch pad: {ctx.counts.get('scratch pages', 0)} pages" + ("" if p else " (no SN store)"))

    p = store_path(snread.EXT_CALENDAR)
    out = work / "calendar.out"
    con = sqlite3.connect(plain_of(p)) if p else sqlite3.connect(":memory:")
    build_calendar(con, out, ctx, now)
    con.close()
    emit(out, S.store_name("calsprout", args.out_variant), S.CALENDAR_USER_VERSION)
    rep.log(f"  calendar: {ctx.counts.get('calendar pages', 0)} ink pages, {ctx.counts.get('events', 0)} events" + ("" if p else " (no SN store)"))

    p = store_path(snread.EXT_BIBLE)
    out = work / "bible.out"
    con = sqlite3.connect(plain_of(p)) if p else sqlite3.connect(":memory:")
    build_bible(con, out, ctx)
    con.close()
    emit(out, S.store_name("biblesprout", args.out_variant), S.BIBLE_USER_VERSION)
    rep.log(f"  bible: position {'kept' if ctx.counts.get('bible position') else 'none'}, {ctx.counts.get('bible recents', 0)} recent chapters" + ("" if p else " (no SN store)"))

    p = store_path(snread.EXT_DOCUMENT)
    if p:
        con = sqlite3.connect(plain_of(p))
        words = count_words(con)
        con.close()
        if words:
            rep.warn(f"the proofread user dictionary ({words} words) stays behind: Soil keeps it on the device, not in the library")
    if store_path(snread.EXT_CLOUD):
        rep.log("  · the cloud account stays behind: connect Drive again in Sproutscape")

    # ── the index ──
    idx_plain = work / "index.out"
    write_index(idx_plain, sn, items, tags, ctx)
    emit(idx_plain, "soil.db", S.INDEX_USER_VERSION)

    # ── report + hygiene ──
    rep.log("")
    rep.log("Counts:")
    for k in sorted(ctx.counts):
        rep.log(f"  {k}: {ctx.counts[k]}")
    if ctx.counts.get("shape rows"):
        rep.log("")
        rep.log(f"SHAPES: {ctx.counts['shape rows']} shape row(s) carried into the notebook files. Notesprout does not draw them "
                "(BACKLOG.md); they are kept so shapes can be brought back. Greg decides.")
    if rep.warnings:
        rep.log("")
        rep.log(f"Warnings ({len(rep.warnings)}):")
        for w in rep.warnings:
            rep.log(f"  - {w}")
    (dst / "report.txt").write_text("\n".join(rep.lines) + "\n", encoding="utf-8")
    if not args.keep_work:
        for p in work.iterdir():
            cipher.scrub(p)
        shutil.rmtree(work, ignore_errors=True)
    rep.log("")
    rep.log(f"Done → {dst}")
    return 0


def _summary(it) -> str:
    c = it.counts
    if it.kind == S.KIND_DOCUMENT:
        return f" ({c.get('chars', 0)} chars from {c.get('from')})"
    bits = [f"{len(it.page_ids)} pages"]
    for k in ("stroke", "heading", "text", "link", "sticky_note", "shape rows", "sketch_graphite", "sketch_ink"):
        if c.get(k):
            bits.append(f"{c[k]} {k}")
    return " (" + ", ".join(bits) + ")"
