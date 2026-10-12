#!/usr/bin/env python3
"""
RC-I · certification source scope helper.

What this replaces: the SHA-1 in `pipelinek-policy-cli/src/test/resources/cert/SHA.txt`
is the only cert artefact today, and it covers ONLY the root module's
`src/{main,test}/kotlin`. The remaining six included modules
(policy-decoders-{json,yaml,csv,map}, pipelinek-policy-{plugin,cli,parity})
are certified by `certify.sh` counting the tests they ran but the receipt
cannot tell you which sources those tests came from. A reader of the
receipt who wanted to verify "is module X covered by rc.1" had to grep.

The scope this helper produces is settings-authoritative: it reads the
include list from `settings.gradle.kts`, never from a hardcoded list, so
adding or removing a module moves the manifest and the gate refuses to
admit a stale cert.

Scope per module:
  - src/main/kotlin
  - src/test/kotlin
  (and any parity sources when the module is the parity bucket)

Excluded:
  - any path containing "/build/" (compile artefacts, reports)
  - any path inside "policy-fir-spike" branches (the worktree is preserved
    by `git branch`; the cert never claims it)
  - any .jar/.class/.html/.xml produced by Gradle

The legacy SHA-1 over root-only sources is KEPT in the manifest, side by
side with the per-module digests, because `CertificationAnchorTest.01b`
asserts against the legacy value and the anchor contract is the one
falsifiable thing in the project. Renaming the field would break the
test; adding a new field does not.

The script exits 0 with a JSON on stdout, or 1 with an error on stderr.
A non-zero exit is the gate's signal that the source tree cannot be
canonicalised.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys
from typing import Iterable

EXCLUDE_PATH_FRAGMENTS = (
    "/build/",
    "/.gradle/",
    "/.idea/",
    "/node_modules/",
    "/policy-fir-spike/",
    "/out/",
    "/tmp/",
)
KOTLIN_EXT = ".kt"
INCLUDE_BLOCK_RE = re.compile(r"include\s*\((.*?)\)", re.DOTALL)
QUOTED_NAME_RE = re.compile(r'"([^"]+)"')


def settings_modules(root: pathlib.Path) -> list[str]:
    settings = root / "settings.gradle.kts"
    if not settings.is_file():
        die(f"settings.gradle.kts not found at {settings}")
    text = settings.read_text(encoding="utf-8")
    m = INCLUDE_BLOCK_RE.search(text)
    if not m:
        die("no `include(...)` block found in settings.gradle.kts")
    return sorted(QUOTED_NAME_RE.findall(m.group(1)))


def module_kotlin_files(
    root: pathlib.Path,
    module: str,
) -> list[pathlib.Path]:
    """Return sorted Kotlin source paths belonging to a Gradle module.

    The module directory mirrors the include name (`policy-decoders-csv` ->
    `./policy-decoders-csv/`). A future settings that uses `:foo:bar` style
    paths would need this resolved against the project tree; the current
    tree uses flat names, so we stay literal.

    The literal name `root` is reserved for the implicit root project:
    its sources live at `./src/...`, not `./root/src/...`.
    """
    mod_root = root if module == "root" else root / module
    if not mod_root.is_dir():
        die(f"module directory missing: {mod_root}")
    candidates: list[pathlib.Path] = []
    for sub in ("src/main/kotlin", "src/test/kotlin"):
        base = mod_root / sub
        if not base.is_dir():
            continue
        for p in base.rglob(f"*{KOTLIN_EXT}"):
            candidates.append(p)
    return sorted(candidates, key=lambda p: str(p.relative_to(root)))


def filter_paths(root: pathlib.Path, paths: Iterable[pathlib.Path]) -> list[pathlib.Path]:
    out: list[pathlib.Path] = []
    for p in paths:
        rel = str(p.relative_to(root))
        if any(frag in rel for frag in EXCLUDE_PATH_FRAGMENTS):
            continue
        if p.suffix != KOTLIN_EXT:
            continue
        out.append(p)
    return out


def sha1_paths(root: pathlib.Path, paths: Iterable[pathlib.Path]) -> str:
    """SHA-1 over sorted (path, contents) tuples. Matches the algorithm
    `CertificationAnchorTest.sourceTreeSha` and `scripts/m6/certify.sh`
    use, so the three callers agree on what a source tree SHA means."""
    h = hashlib.sha1()
    sorted_paths = sorted(paths, key=lambda p: str(p.relative_to(root)))
    for p in sorted_paths:
        rel = str(p.relative_to(root))
        h.update(rel.encode("utf-8"))
        h.update(p.read_bytes())
    return h.hexdigest()


def legacy_root_sha(root: pathlib.Path) -> tuple[str, int]:
    """The legacy anchor: SHA-1 over the root module's src/{main,test}/kotlin
    Kotlin files. Same algorithm, same scope as `cert/SHA.txt` and
    `CertificationAnchorTest.sourceTreeSha`. The cert is the legacy
    anchor; this function exists so the manifest can carry it forward
    without dragging the test's exact body in here."""
    paths: list[pathlib.Path] = []
    for sub in ("src/main/kotlin", "src/test/kotlin"):
        base = root / sub
        if not base.is_dir():
            continue
        for p in base.rglob(f"*{KOTLIN_EXT}"):
            rel = str(p.relative_to(root))
            if any(frag in rel for frag in EXCLUDE_PATH_FRAGMENTS):
                continue
            paths.append(p)
    return sha1_paths(root, paths), len(paths)


def die(msg: str) -> "NoReturn":  # type: ignore[name-defined]
    print(f"certification_source_scope: {msg}", file=sys.stderr)
    sys.exit(1)


def build_manifest(root: pathlib.Path) -> dict:
    # The settings include() block lists subprojects, but the ROOT project
    # is implicit: Gradle always has a root, and its sources live at
    # `<root>/src/...`, not under any subproject directory. Treating the
    # include list as the full source-scope would silently drop every root
    # file, which is the opposite of what the anchor is for. The root is
    # added as a virtual module named "root" so the manifest's per-module
    # sum and the legacy root-only digest are both honest.
    modules = ["root", *settings_modules(root)]
    module_entries: list[dict] = []
    all_files: list[pathlib.Path] = []
    for m in modules:
        files = filter_paths(root, module_kotlin_files(root, m))
        module_entries.append(
            {
                "module": m,
                "file_count": len(files),
                "sha1": sha1_paths(root, files),
                "files": [str(p.relative_to(root)) for p in files],
            }
        )
        all_files.extend(files)
    legacy_sha, legacy_count = legacy_root_sha(root)
    return {
        "schema": "pipelinek-policy.certification.source-scope/v1",
        "settings": "settings.gradle.kts",
        "modules": module_entries,
        "all_modules": {
            "file_count": len(all_files),
            "sha1": sha1_paths(root, all_files),
        },
        "legacy_root_only": {
            "scope": "root/src/{main,test}/kotlin",
            "file_count": legacy_count,
            "sha1": legacy_sha,
        },
        "excluded_path_fragments": list(EXCLUDE_PATH_FRAGMENTS),
    }


class _SnapshotAction(argparse.Action):
    """`snapshot` and `print` are equivalent in the JSON-only build, but
    keeping both as first-class subcommands lets call-sites stay readable
    (`snapshot` for "produce the cert artefact", `print` for "show it").
    The legacy form had no subcommand and accepted flags only; both forms
    are kept below for back-compat with any existing ad-hoc invocations.
    """

    def __init__(self, option_strings, dest, **kwargs):
        super().__init__(option_strings, dest, nargs=0, **kwargs)

    def __call__(self, parser, namespace, values, option_string=None):
        setattr(namespace, "subcommand", self.dest)


def main() -> int:
    p = argparse.ArgumentParser(
        description=(
            "Emit a settings-authoritative certification source-scope "
            "manifest. RC-I replaces the single SHA in cert/SHA.txt with "
            "a per-module digest plus a flat all-modules digest, while "
            "preserving the legacy root-only SHA for the existing "
            "CertificationAnchorTest contract."
        )
    )
    p.add_argument(
        "subcommand",
        nargs="?",
        choices=("snapshot", "print"),
        default="print",
        help="snapshot writes to --out; print writes to stdout (default: print)",
    )
    p.add_argument(
        "--root",
        type=pathlib.Path,
        default=pathlib.Path("."),
        help="repo root (default: cwd)",
    )
    p.add_argument(
        "--format",
        choices=("json",),
        default="json",
        help="output format (json only for now)",
    )
    p.add_argument(
        "--out",
        type=pathlib.Path,
        default=None,
        help="write the manifest to this path instead of stdout (default: only when subcommand=snapshot)",
    )
    p.add_argument(
        "--snapshot",
        action=_SnapshotAction,
        dest="snapshot",
        help="alias for the `snapshot` subcommand (kept for ad-hoc invocations)",
    )
    p.add_argument(
        "--print",
        action=_SnapshotAction,
        dest="print",
        help="alias for the `print` subcommand",
    )
    args = p.parse_args()
    # When the subcommand is `snapshot` and --out was not given, default the
    # output path to `<root>/build/reports/certification/source-scope.json`
    # so the cert artefact lands in a Gradle-shaped location and the
    # `releaseEvidenceManifest` Gradle task can read it without ceremony.
    root = args.root.resolve()
    if args.subcommand == "snapshot" and args.out is None:
        args.out = root / "build" / "reports" / "certification" / "source-scope.json"
    manifest = build_manifest(root)
    payload = json.dumps(manifest, indent=2, sort_keys=True) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(payload, encoding="utf-8")
    else:
        sys.stdout.write(payload)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
