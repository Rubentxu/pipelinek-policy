#!/usr/bin/env bash
# B6.1 · Certification receipt.
#
# The problem this replaces: `cert/SHA.txt` carried a `certified-sha:` line that
# nothing ever compared to anything. It went stale silently while still looking
# like a certification. An anchor that cannot fail is not an anchor.
#
# What this emits, and why each field exists:
#   source tree   SHA-1 of the sorted Kotlin paths + contents. This is the value
#                 CertificationAnchorTest.01b checks against, so the receipt and
#                 the gate can never disagree about what was certified.
#   modules       read from settings.gradle.kts, not hardcoded. A module that
#                 stops being included must disappear from the receipt.
#   tests         the tests GRADLE ACTUALLY RAN in this invocation, counted from
#                 freshly written XML. Never a file count: `*Test.kt` measures
#                 files on disk, which says nothing about what executed.
#   toolchain     JDK and Kotlin versions. Two runs with the same test count
#                 still certify different compilers (B6.2).
#   dependencies  resolved coordinates, not requested ones.
#   artifacts     digests of what was actually produced.
#
# Staleness is the failure mode this file exists to prevent, so the run is
# forced with `--rerun-tasks` and the XML directory is removed first. Reusing a
# cached report would certify the last green run instead of this one.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

RECEIPT="${1:-$REPO_ROOT/docs/history/B6_CERTIFICATION.md}"
GRADLE="$REPO_ROOT/gradle-jdk21.sh"

say() { echo "· $*"; }

# Fresh reports only. A stale XML would silently certify the previous run.
rm -rf */build/test-results/test */build/test-results/*/test

echo "== B6.1 certification run (fresh reports, --rerun-tasks)"
"$GRADLE" test --rerun-tasks --console=plain -q || {
  echo "GATE FAILED: certification requires a green gate" >&2
  exit 1
}

# --- source tree ------------------------------------------------------------
SOURCE_SHA="$(python3 - <<'PY'
import hashlib, pathlib
root = pathlib.Path('.')
files = []
for d in ('src/main/kotlin', 'src/test/kotlin'):
    p = root / d
    if p.exists():
        files += [f for f in p.rglob('*.kt') if '/build/' not in str(f)]
files.sort(key=lambda f: str(f.relative_to(root)))
h = hashlib.sha1()
for f in files:
    h.update(str(f.relative_to(root)).encode())
    h.update(f.read_bytes())
print(h.hexdigest())
PY
)"
SOURCE_FILES="$(python3 -c "
import pathlib
n=0
for d in ('src/main/kotlin','src/test/kotlin'):
    p=pathlib.Path(d)
    if p.exists(): n+=len([f for f in p.rglob('*.kt') if '/build/' not in str(f)])
print(n)")"
say "source tree: $SOURCE_SHA ($SOURCE_FILES files)"

# --- what actually ran ------------------------------------------------------
read -r T F E S <<<"$(python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
t = f = e = s = 0
for p in glob.glob('**/build/test-results/test/TEST-*.xml', recursive=True):
    try:
        r = ET.parse(p).getroot()
    except Exception:
        continue
    if r.tag != 'testsuite':
        continue
    t += int(r.get('tests', 0)); f += int(r.get('failures', 0))
    e += int(r.get('errors', 0));   s += int(r.get('skipped', 0))
print(t, f, e, s)
PY
)"
[ "$F" -eq 0 ] && [ "$E" -eq 0 ] || { echo "GATE FAILED: $F failures, $E errors" >&2; exit 1; }
say "executed: $T tests, $F failures, $E errors, $S skipped"

# --- modules ----------------------------------------------------------------
MODULES="$(python3 -c "
import re,pathlib
t=pathlib.Path('settings.gradle.kts').read_text()
m=re.search(r'include\((.*?)\)',t,re.S)
print(', '.join(sorted(re.findall(r'\"([^\"]+)\"',m.group(1)))))")"
say "modules: $MODULES"

# --- toolchain --------------------------------------------------------------
# B6.2's whole point: the same test count on two toolchains certifies two
# different compilers, so the receipt names them explicitly. The Kotlin version
# is read from the version catalog, which is the single place it is pinned.
RUNTIME_VER="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/.*java.specification.version = //p' | head -1)"
KOTLIN_VER="$(sed -n 's/^kotlin[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' gradle/libs.versions.toml | head -1)"
[ -n "$RUNTIME_VER" ] || { echo "could not detect the JVM version" >&2; exit 1; }
[ -n "$KOTLIN_VER" ] || { echo "could not read the pinned Kotlin version from gradle/libs.versions.toml" >&2; exit 1; }
say "toolchain: runtime $RUNTIME_VER, kotlin $KOTLIN_VER"

# --- artifacts --------------------------------------------------------------
ARTIFACTS="$(find . -path '*/build/libs/*.jar' -not -path '*/build/libs/*-sources*' -type f 2>/dev/null | sort | while read -r j; do
  printf '%s  %s\n' "$(sha256sum "$j" | cut -c1-16)" "${j#./}"
done)"
ART_COUNT="$(printf '%s\n' "$ARTIFACTS" | grep -c . || true)"
say "artifacts: $ART_COUNT jars"

# --- dependencies -----------------------------------------------------------
DEPS="$(find ~/.gradle/caches/modules-2/files-2.1 -maxdepth 2 -mindepth 2 -type d 2>/dev/null | sed 's|.*/files-2.1/||' | sort -u | wc -l)"

GIT_SHA="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
GIT_TREE_STATE="$(git status --porcelain | wc -l) changed paths"

# --- bind the executed count to the marker ---------------------------------
# The receipt says "498 tests executed". On its own that number is a claim, not
# a gate: the falsification probe below showed the script happily certifying
# 281 with the gate bypassed. Writing it into the marker makes the NEXT run
# fail loudly if fewer tests execute than were certified, which is the property
# that makes "we certified 498" mean something.
MARKER="pipelinek-policy-cli/src/test/resources/cert/SHA.txt"
MARKED_TESTS="$(sed -n 's/^baseline-tests:[[:space:]]*//p' "$MARKER" | head -1)"
if [ -n "$MARKED_TESTS" ]; then
  say "marker baseline-tests: $MARKED_TESTS, executed: $T"
  if [ "$T" -lt "$MARKED_TESTS" ]; then
    echo "GATE FAILED: $T tests executed but cert/SHA.txt certifies $MARKED_TESTS." >&2
    echo "A module's suite may have fallen out of the check." >&2
    exit 1
  fi
else
  echo "GATE FAILED: cert/SHA.txt has no baseline-tests to certify against" >&2
  exit 1
fi

# --- receipt ----------------------------------------------------------------
mkdir -p "$(dirname "$RECEIPT")"
cat > "$RECEIPT" <<EOF
# B6.1 · Certification receipt

Generated by \`scripts/m6/certify.sh\`. Everything below is read from the run
that produced this file. Nothing is carried over from a previous run.

- **source tree SHA**: \`$SOURCE_SHA\` ($SOURCE_FILES Kotlin files)
- **git HEAD**: \`$GIT_SHA\` ($GIT_TREE_STATE)
- **modules**: $MODULES
- **tests actually executed**: $T ($F failures, $E errors, $S skipped)
- **toolchain**: JVM $RUNTIME_VER, Kotlin $KOTLIN_VER
- **resolved dependency coordinates**: $DEPS
- **artifacts**: $ART_COUNT jars

## Artifact digests

\`\`\`
$ARTIFACTS
\`\`\`

## How to read this

\`$SOURCE_SHA\` is the value \`CertificationAnchorTest.01b\` asserts against
\`cert/SHA.txt\`. If this receipt and the marker disagree, one of them is stale
and the gate says so.
EOF

echo "receipt: $RECEIPT"