#!/usr/bin/env bash
# M10 REQ 05c · On-demand 1 GiB CSV characterization (NOT in CI).
# Budget: expect ~2-6 GB heap; run with a beefy JAVA_OPTS if needed.
set -euo pipefail
cd "$(dirname "$0")/../.."

TARGET="${1:-1073741824}"
OUT="${2:-docs/history/M10_CSV_1GIB_RECEIPT.md}"

echo "Running CertCsvMain with target bytes: $TARGET"
RUN=$(
  ./gradle-jdk21.sh -q :pipelinek-policy-cli:certCsv1gib \
    -PcertCsvBytes="$TARGET" --console=plain 2>/dev/null
)

{
  echo "# M10 CSV 1 GiB Receipt (on-demand, 05c)"
  echo
  echo '```'
  echo "$RUN"
  echo '```'
  echo
  echo "Executed: $(date -u +%Y-%m-%dT%H:%M:%SZ) · target: $TARGET bytes"
  echo "CI budget: 64 MiB (CsvCharacterizationTest). This run is operator-triggered."
} > "$OUT"

echo "Receipt written to $OUT"
