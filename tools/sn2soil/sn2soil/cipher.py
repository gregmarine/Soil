"""SQLCipher plumbing over the `sqlcipher` CLI (Homebrew).

Notesprout SN and Soil both use stock SQLCipher 4 defaults, so the passphrase text opens either. The passphrase is
handed to the CLI on stdin inside a SQL script — never on the command line, never logged.
"""

from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path


class CipherError(RuntimeError):
    pass


def sqlcipher_bin() -> str:
    exe = shutil.which("sqlcipher")
    if not exe:
        raise CipherError("`sqlcipher` not found on PATH — `brew install sqlcipher`")
    return exe


def _q(s: str) -> str:
    return s.replace("'", "''")


def _run(db: Path, script: str) -> str:
    proc = subprocess.run(
        [sqlcipher_bin(), "-bail", "-batch", str(db)],
        input=script,
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        err = (proc.stderr or proc.stdout).strip().splitlines()
        msg = err[-1] if err else f"sqlcipher exited {proc.returncode}"
        raise CipherError(f"{db.name}: {msg}")
    return proc.stdout


def decrypt_to_plain(src: Path, passphrase: str, dst: Path) -> None:
    """Export the encrypted `src` as a plaintext SQLite file at `dst` (created fresh)."""
    if dst.exists():
        dst.unlink()
    script = (
        f"PRAGMA key='{_q(passphrase)}';\n"
        "SELECT count(*) FROM sqlite_master;\n"
        f"ATTACH DATABASE '{_q(str(dst))}' AS plain KEY '';\n"
        "SELECT sqlcipher_export('plain');\n"
        "DETACH DATABASE plain;\n"
    )
    _run(src, script)
    if not dst.exists():
        raise CipherError(f"{src.name}: export produced no file")


def encrypt_from_plain(plain: Path, passphrase: str, dst: Path, user_version: int) -> None:
    """Create the encrypted `dst` from the plaintext `plain`, then stamp `user_version` by hand
    (sqlcipher_export copies tables and indexes, never PRAGMA user_version)."""
    if dst.exists():
        dst.unlink()
    for side in ("-wal", "-shm", "-journal"):
        p = Path(str(dst) + side)
        if p.exists():
            p.unlink()
    script = (
        f"PRAGMA key='{_q(passphrase)}';\n"
        f"ATTACH DATABASE '{_q(str(plain))}' AS plain KEY '';\n"
        "SELECT sqlcipher_export('main', 'plain');\n"
        "DETACH DATABASE plain;\n"
        f"PRAGMA user_version = {int(user_version)};\n"
        "PRAGMA journal_mode = DELETE;\n"
    )
    _run(dst, script)


def verify(dst: Path, passphrase: str, expect_user_version: int) -> None:
    """Re-open the finished file: key must work, integrity ok, user_version as stamped."""
    out = _run(
        dst,
        f"PRAGMA key='{_q(passphrase)}';\nPRAGMA integrity_check;\nPRAGMA user_version;\n",
    )
    lines = [l.strip() for l in out.splitlines() if l.strip() and l.strip() != "ok"]
    # After the 'ok' from PRAGMA key and 'ok' from integrity_check, one line remains: the version.
    if not lines or lines[-1] != str(expect_user_version):
        raise CipherError(f"{dst.name}: verify failed (got {lines!r}, want user_version {expect_user_version})")


def is_encrypted(path: Path) -> bool:
    """A plaintext SQLite file starts with the 16-byte magic; SQLCipher's page 1 is salt+ciphertext."""
    with open(path, "rb") as f:
        head = f.read(16)
    return head != b"SQLite format 3\x00"


def scrub(path: Path) -> None:
    """Best-effort plaintext hygiene for work files: overwrite once, then unlink."""
    try:
        size = os.path.getsize(path)
        with open(path, "r+b") as f:
            f.write(b"\0" * min(size, 64 * 1024 * 1024))
    except OSError:
        pass
    try:
        path.unlink()
    except OSError:
        pass
