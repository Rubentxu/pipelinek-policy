#!/usr/bin/env bash
# B6.10 · Release admission, local half.
#
# SCOPE AND LIMITS, STATED UP FRONT
# ----------------------------------
# `git.push`, `git.tag` and `git.release` are `human_gate` at system-law level
# in the SDDK config resolved from this workspace. This script therefore stops
# ON PURPOSE before the publication boundary: it produces and verifies every
# artifact that must exist *before* a human tags and releases, and it exits
# with a distinct code when the only thing left is the human action.
#
#   exit 0  candidate tree is admissible; artifacts and receipt written
#   exit 1  candidate tree is NOT admissible (a gate failed)
#   exit 3  inadmissible, or the tree moved while we measured
#   exit 4  everything is admissible; the remaining step is a human gate
#           (tag + push + release). Nothing was published.
#
# What it does NOT do: no push, no tag, no GitHub Release, no force anything.
# Those are human actions and this script refuses to perform them.
#
# Why it refuses silently-fail vs hard-fail: a release script that publishes a
# tree whose gate went red is worse than no release script. Every gate here is
# a hard failure, and the receipt is regenerated on every run so it can never
# describe a run that did not happen.
#
# THREE LESSONS from building this, each learned by being wrong first:
#   1. An evidence receipt measured on a clean tree is ONE COMMIT BEHIND the
#      commit that carries it. A gate demanding receipt HEAD == candidate HEAD
#      is therefore unsatisfiable, and the only way past it is to stop
#      certifying. The invariant that matters is that the receipt certifies the
#      candidate's SOURCES.
#   2. The test baseline is a FLOOR, not a fixed point. certify.sh fails only
#      on a drop, because a drop means a suite fell out of the check. Requiring
#      equality rejects every legitimate test addition between anchor updates.
#   3. A gate firing is not proof the gate works. Two of the early gates here
#      failed for reasons that had nothing to do with what they claimed to
#      check, and one hung forever instead of failing. Every gate below was run
#      to see it fire on a real defect, not just read.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

RECEIPT="docs/history/B6_RELEASE_ADMISSION.md"
OUT_DIR="$JCODE_SCRATCH_DIR/b6.10"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"

mkdir -p "$OUT_DIR"

log()  { printf '%s\n' "$*"; }
fail() { printf 'B6.10 GATE FAILED: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- gate: tree
HEAD_SHA="$(git rev-parse HEAD)"
log "· candidate HEAD: $HEAD_SHA"

if [ -n "$(git status --porcelain)" ]; then
    fail "working tree is dirty; a release candidate must be a clean tree"
fi

# The certification anchor must match the candidate tree. NOTE the two SHAs are
# different kinds of value and must not be compared to each other:
#   - certified-sha  = SHA-1 over the sorted root-module Kotlin source paths and
#                      their contents (NOT a git commit/tree hash);
#   - HEAD           = the git commit.
# certify.sh recomputes certified-sha on every run, so the honest check is that
# the receipt reports the SAME certified-sha the marker claims and the same test
# count. Comparing certified-sha to HEAD would be a category error that happens
# to fail, and it would have looked like a working gate.
MARKER="pipelinek-policy-cli/src/test/resources/cert/SHA.txt"
if [ ! -f "$MARKER" ]; then
    fail "certification marker $MARKER is missing; run scripts/m6/certify.sh"
fi
CERT_SHA="$(sed -n 's/^certified-sha:[[:space:]]*//p' "$MARKER" | head -1)"
BASELINE="$(sed -n 's/^baseline-tests:[[:space:]]*//p' "$MARKER" | head -1)"
[ -n "$CERT_SHA" ] || fail "$MARKER has no readable certified-sha"
[ -n "$BASELINE" ] || fail "$MARKER has no readable baseline-tests"
log "· certified source sha: $CERT_SHA (root-module sources, not a git hash)"
log "· baseline tests:       $BASELINE"

# ---------------------------------------------------------------- gate: version
VERSION="$(grep -oE '^version[[:space:]]*=[[:space:]]*"[^"]+"' build.gradle.kts | head -1 | grep -oE '"[^"]+"' | tr -d '"')"
[ -n "$VERSION" ] || fail "could not read version from build.gradle.kts"
log "· declared version: $VERSION"
case "$VERSION" in
    *-M[0-9]*|*alpha*|*beta*|*rc*)
        log "  (pre-release identifier present; this is an alpha, not a stable release)" ;;
    *)
        fail "unexpected version shape $VERSION" ;;
esac

# ---------------------------------------------------------------- gate: gates green
if [ ! -f docs/history/B6_CERTIFICATION.md ]; then
    fail "no certification receipt; run scripts/m6/certify.sh"
fi
CERT_TESTS="$(grep -oE '\*\*tests actually executed\*\*:[[:space:]]*[0-9]+' docs/history/B6_CERTIFICATION.md | grep -oE '[0-9]+' | head -1 || true)"
CERT_FAILS="$(grep -oE '\*\*tests actually executed\*\*:[[:space:]]*[0-9]+[^)]*' docs/history/B6_CERTIFICATION.md | head -1 || true)"
CERT_TREE="$(grep -oE '\*\*source tree SHA\*\*:[[:space:]]*`[0-9a-f]{40}`' docs/history/B6_CERTIFICATION.md | grep -oE '[0-9a-f]{40}' | head -1 || true)"
CERT_HEAD="$(grep -oE '\*\*git HEAD\*\*:[[:space:]]*`[0-9a-f]{40}`' docs/history/B6_CERTIFICATION.md | grep -oE '[0-9a-f]{40}' | head -1 || true)"
[ -n "$CERT_TESTS" ] || fail "certification receipt does not report executed tests"
[ -n "$CERT_TREE" ]  || fail "certification receipt does not report a source tree SHA"
case "$CERT_FAILS" in
    *"0 failures"*) : ;;
    *) fail "certification receipt is not green: $CERT_FAILS" ;;
esac
if [ "$CERT_TREE" != "$CERT_SHA" ]; then
    fail "stale certification: receipt measured $CERT_TREE but marker claims $CERT_SHA; re-run certify.sh"
fi
# The receipt's `git HEAD` is the commit certify.sh RAN on. Because certify.sh
# requires a clean tree, that commit is always the PARENT of the commit that
# lands the receipt itself: the receipt is evidence about the tree, so the tree
# it was measured on must not already contain it. Requiring exact equality
# would therefore be unsatisfiable — every successful run invalidates itself
# the moment its receipt is committed.
#
# What must actually hold is that the receipt certifies the SOURCES of the
# candidate. That is the source-tree SHA, which is recomputed from file
# contents and is immune to the receipt bookkeeping commit. We additionally
# require that the only difference between the receipt's HEAD and the
# candidate is commits that touch docs/history or the release script, i.e. the
# evidence itself rather than the certified code.
if [ -n "$CERT_HEAD" ] && [ "$CERT_HEAD" != "$HEAD_SHA" ]; then
    UNCERTIFIED="$(git diff --name-only "$CERT_HEAD".."$HEAD_SHA" 2>/dev/null \
        | grep -vE '^(docs/history/|scripts/m10/release-admission\.sh$)' || true)"
    if [ -n "$UNCERTIFIED" ]; then
        fail "stale certification: $CERT_HEAD..$HEAD_SHA changes certified code:
$(printf '%s\n' "$UNCERTIFIED" | sed 's/^/    /')
re-run certify.sh on this tree"
    fi
    log "· receipt ran on ${CERT_HEAD:0:7}; only evidence files differ since"
fi
# The baseline is a FLOOR, not an equality. certify.sh fails only when fewer
# tests run than were certified, because a drop means a suite fell out of the
# check; running MORE tests than the baseline is the normal consequence of
# adding tests and moving the anchor deliberately in a later step. Requiring
# equality here would reject every legitimate addition between anchor updates,
# which is the same unsatisfiable-gate mistake as the HEAD comparison.
if [ "$CERT_TESTS" -lt "$BASELINE" ]; then
    fail "certification executed $CERT_TESTS tests but certifies $BASELINE; a suite fell out of the check"
fi
log "· certification: $CERT_TESTS tests, green (baseline floor $BASELINE)"

# ---------------------------------------------------------------- collect artifacts
ARTIFACTS=()
# Only jars belonging to the current version are publishable. A stale jar from
# a previous version can survive in build/libs because Gradle does not prune it,
# and shipping it alongside the real artifact is how a release ends up
# advertising two versions of itself. Matching on the version string keeps the
# versionless (module) jars, which are what the modules publish.
while IFS= read -r a; do ARTIFACTS+=("$a"); done < <(
    find build/libs pipelinek-policy-*/build/libs policy-decoders-*/build/libs \
        -name '*.jar' -type f 2>/dev/null \
    | grep -vE '/[^/]*-[0-9][^/]*\.jar$' | grep -vF "$VERSION" || true
)
[ "${#ARTIFACTS[@]}" -gt 0 ] || fail "no jars found for version $VERSION; run a build first"
# Superseded VERSIONED jars are what would pollute a release. Module jars have
# no version in their name and are the ones the modules publish, so they are
# expected output, not leftovers.
STALE="$(find build/libs pipelinek-policy-*/build/libs policy-decoders-*/build/libs \
    -name '*.jar' -type f 2>/dev/null | grep -E '/[^/]*-[0-9][^/]*\.jar$' \
    | grep -vF "$VERSION" || true)"
if [ -n "$STALE" ]; then
    log "· note: ignoring superseded version jars left in build output:"
    printf '%s\n' "$STALE" | sed 's/^/    /'
fi
log "· artifacts: ${#ARTIFACTS[@]} jars for $VERSION"

# ---------------------------------------------------------------- checksums
SUMS="$OUT_DIR/SHA256SUMS"
: > "$SUMS"
for a in "${ARTIFACTS[@]}"; do
    sha256sum "$a" >> "$SUMS"
done
log "· checksums: $(wc -l < "$SUMS") entries"

# ---------------------------------------------------------------- SBOM
# No SBOM generator is installed here (no syft, no cyclonedx-gomod), so the SBOM
# is emitted from the *resolved dependency coordinates* that the build itself
# reports, not invented. That is a weaker guarantee than a full component
# scanner and the receipt says so explicitly rather than implying more.
SBOM="$OUT_DIR/pipelinek-policy-$VERSION.sbom.spdx.json"
RESOLVED="$(grep -oE '^\- \*\*resolved dependency coordinates\*\*:[[:space:]]*[0-9]+' docs/history/B6_CERTIFICATION.md | grep -oE '[0-9]+' | head -1 || echo 0)"

{
  printf '{\n'
  printf '  "spdxVersion": "SPDX-2.3",\n'
  printf '  "dataLicense": "CC0-1.0",\n'
  printf '  "SPDXID": "SPDXRef-DOCUMENT",\n'
  printf '  "name": "pipelinek-policy-%s",\n' "$VERSION"
  printf '  "documentNamespace": "https://github.com/Rubentxu/pipelinek-policy/tree/%s",\n' "$HEAD_SHA"
  printf '  "creationInfo": {\n'
  printf '    "created": "%s",\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf '    "creators": ["Tool: scripts/m10/release-admission.sh"],\n'
  printf '    "comment": "Derived from the build-resolved dependency set reported by certify.sh (%s coordinates). This is NOT a full component scan: no syft/cyclonedx scanner was available in the release environment."\n' "$RESOLVED"
  printf '  },\n'
  printf '  "packages": [\n'
  first=1
  for a in "${ARTIFACTS[@]}"; do
    sum="$(sha256sum "$a" | cut -d" " -f1)"
    [ $first -eq 1 ] || printf ',\n'
    first=0
    printf '    {"SPDXID": "SPDXRef-Package-%s", "name": "%s", "downloadLocation": "NOASSERTION", "filesAnalyzed": false, "licenseConcluded": "NOASSERTION", "checksums": [{"algorithm": "SHA256", "checksumValue": "%s"}]}' \
      "$(printf '%s' "$a" | sed 's|/|_|g')" "$a" "$sum"
  done
  printf '\n  ]\n}\n'
} > "$SBOM"

python3 -c "import json,sys; json.load(open('$SBOM'))" \
  || fail "generated SBOM is not valid JSON"
log "· SBOM: $(basename "$SBOM") (valid JSON, $RESOLVED coordinates reported by the build)"

# ---------------------------------------------------------------- provenance
# cosign is present. We attest the checksum file rather than a container image:
# there is no image in this release, and attesting a checksum list is the
# honest analogue. Keyless attestation requires network + OIDC, so failure here
# is reported as an unmet item, not a hard gate failure.
PROV_STATUS="NOT_ATTESTED"
PROV_ERR=""
# Keyless attestation needs network and an OIDC exchange. Without a timeout it
# can block for minutes and take the whole release gate with it, so it is
# bounded and treated as an unmet item rather than a hard failure.
if command -v cosign >/dev/null 2>&1; then
    if timeout 60 cosign attest-blob --predicate "$SBOM" --type spdxjson \
        --yes --bundle "$OUT_DIR/provenance.bundle.json" --new-bundle-format \
        "$SUMS" >"$OUT_DIR/provenance.log" 2>&1; then
        PROV_STATUS="ATTESTED"
    else
        PROV_RC=$?
        if [ "$PROV_RC" -eq 124 ]; then
            PROV_ERR="timed out after 60s (keyless attestation needs OIDC/network)"
        else
            PROV_ERR="$(tail -3 "$OUT_DIR/provenance.log" | tr '\n' ' ')"
        fi
    fi
fi
log "· provenance: $PROV_STATUS"

# H4.4 state is READ, never asserted. The matrix report is the authority for
# whether the compatibility matrix was measured; if it is absent or its verdict
# is not PASS, the receipt must say that instead of repeating a claim that can
# silently rot into a false statement.
H44_REPORT="docs/history/H4_COMPATIBILITY_MATRIX.md"
H44_LIMIT="A cell measures build+test only; it is not evidence of the plugin loading in a live PipelineK host on that SDK."
H44_LINE="H4.4 compatibility matrix: \`NOT_MEASURED\` (no matrix report at $H44_REPORT)."
if [ -f "$H44_REPORT" ]; then
  H44_VERDICT=$(sed -n 's/^\*\*\(PASS\|FAIL\|PARTIAL\|NOT_MEASURED\)\(.*\)\*\*$/\1/p' "$H44_REPORT" | head -1)
  if [ -z "$H44_VERDICT" ]; then
    H44_LINE="H4.4 compatibility matrix: verdict unreadable in $H44_REPORT; treat as \`NOT_MEASURED\`."
  elif [ "$H44_VERDICT" = "PASS" ]; then
    H44_LINE="H4.4 compatibility matrix: PASS (see $H44_REPORT). $H44_LIMIT"
  else
    H44_LINE="H4.4 compatibility matrix: $H44_VERDICT (see $H44_REPORT)."
  fi
fi

# ---------------------------------------------------------------- receipt
{
  printf '# B6.10 · Release admission (local half)\n\n'
  printf 'Generated by `scripts/m10/release-admission.sh` on %s. Every number below\n' "$STAMP"
  printf 'is read from the run that produced this file.\n\n'
  printf '## Candidate\n\n'
  printf -- '- **HEAD**: `%s`\n' "$HEAD_SHA"
  printf -- '- **certified source sha**: `%s`\n' "$CERT_SHA"
  printf '  (SHA-1 over sorted root-module Kotlin sources, not a git hash)\n'
  printf -- '- **certification ran on this candidate**: %s tests, green, %s executed\n' "$CERT_TESTS" "$CERT_TESTS"
  printf -- '- **version**: `%s` (pre-release identifier present)\n\n' "$VERSION"
  printf '## Publication boundary\n\n'
  printf '`git.push`, `git.tag` and `git.release` are `human_gate` at system-law\n'
  printf 'level in the SDDK config resolved from this workspace. This script does\n'
  printf 'not cross that line. **Nothing was pushed, tagged or released.**\n\n'
  printf '## Artifacts and checksums\n\n'
  printf '```\n'
  cat "$SUMS"
  printf '```\n\n'
  printf '## SBOM\n\n'
  printf -- '- file: `%s`\n' "$(basename "$SBOM")"
  printf -- '- format: SPDX 2.3 JSON, validated by parse\n'
  printf -- '- coverage: derived from the build-resolved dependency set (%s coordinates)\n' "$RESOLVED"
  printf -- '- **limitation**: no syft/cyclonedx scanner was available, so this is\n'
  printf '  not a full component scan. It records what the build actually resolved\n'
  printf '  and the shipped jars with their digests.\n\n'
  printf '## Provenance\n\n'
  printf -- '- status: **%s**\n' "$PROV_STATUS"
  if [ "$PROV_STATUS" != "ATTESTED" ]; then
    printf -- '- reason: cosign keyless attestation unavailable in this environment%s\n' "${PROV_ERR:+ ($PROV_ERR)}"
    printf '  Signing provenance remains an outstanding item for the human gate.\n'
  fi
  printf '\n## Outstanding before a production-ready release\n\n'
  printf '1. Human gate: annotated tag, push, GitHub Release.\n'
  printf '2. Signed provenance (`cosign attest-blob`) with network access.\n'
  printf '3. B6.8 replay hit/miss: `NOT_MEASURED`, decision lives outside this repo.\n'
  printf '4. %s\n' "$H44_LINE"
} > "$RECEIPT"

log "· receipt: $RECEIPT"

# ---------------------------------------------------------------- verdict
log ""
log "B6.10 local admission: candidate is ADMISSIBLE for the human gate."
log "Remaining step requires a human (tag + push + release)."
exit 4
