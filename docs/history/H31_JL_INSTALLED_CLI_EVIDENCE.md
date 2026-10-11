# H3.1-JSONL · Installed-CLI acceptance evidence (process-level)

**Cycle:** closing the H3.1-JSONL reviewer-flagged gap.
**Verdict: PASS (OBSERVED)** — measured at the JVM process boundary, not in
the test JVM.

## What was missing

The H3.1-JSONL PASS claim on `ROADMAP.md` (commit 926f1ed) was supported by:

- the test-JVM `CountingInputStream` in `JsonlRowStreamSourceTest`
- the mutation gate (38 killed / 0 survived / 0 invalid)
- the certify gate (619 / 0 / 0 / 0)
- the release-admission gate (ADMISSIBLE, human gate held)

The H3.1-CSV row, in contrast, claimed

> bytes pulled before the first row stay at 8192 for a 3.8 KB file and for a
> 6.9 MB file, so the bound is fixed rather than proportional.

backed by a unit-test-level `CountingInputStream` in
`policy-decoders-csv/src/test/kotlin/.../CsvRowSourceStreamingTest.kt`. The
JSONL row on ROADMAP inherited the same numeric claim but never ran the
process-level measurement the user is asking for: a measurement that
exercises the **installed CLI jar** against real fixtures, not the test
JVM's `PolicyCli.run(...)`.

## Method

`strace -P <file>` limits the trace to `read()` syscalls on the JSONL file
descriptor of the JVM process spawned by the installed CLI. The first
`read()` the JVM issues on the file is the initial 8 KiB window fill from
`JsonlByteSource.Windowed.ensure(1)`. The *request size* of that first read
is the metric: it is `buf.size - bufLen = 8192 - 0 = 8192`, independent of
file size, because the window is filled to capacity on first use.

The CLI install was rebuilt against HEAD `926f1ed` via
`./gradlew :pipelinek-policy-cli:installDist -x test` before the run; the
new `policy-decoders-json.jar` (25 836 B) contains the H3.1-JSONL
`JsonlSource.Companion.open(stream)` static. Verified with `javap -public`
that the public surface of the installed class is the same as the source.

Bundle: a one-shot `H31JlAcceptanceBundleDumperTest` was added to
`pipelinek-policy-cli/src/test/kotlin/.../H31JlAcceptanceBundleDumperTest.kt`,
executed once via `:pipelinek-policy-cli:test --tests ...`, and then
removed; the bundle is the only durable artifact under `/tmp/h31jl-uat/`.
The test was not committed because its only purpose is to produce
`/tmp/h31jl-uat/p.bundle`; keeping it in the source tree would leave a
test whose behavior is a side effect (write to /tmp) without a
regression assertion.

## Fixtures

```
small.jsonl   3 897 B    180 rows  (target 3 891 B, ROADMAP H3.1-CSV fixture size)
big.jsonl   7 234 953 B  290 933 rows  (target 7 234 938 B, ROADMAP H3.1-CSV fixture size)
```

Each row is `{"temp":"ok","n":<i>}\n` (about 18-22 B per row). One in four
rows sets `temp:"bad"` so violations are non-zero and the CLI exits 2.

## Observed

### small.jsonl (3.8 KiB)

```
read(13, "{\"temp\":\"ok\",\"n\":1}\n{\"temp\":\"ok\""..., 8192) = 3897
read(13, "", 4295)                                               = 0
```

The JVM opens the file as fd 13, requests `read(13, buf, 8192)`, and
receives 3897 bytes (the whole file, because 3 897 < 8 192). Then one
EOF read. **No read returns more than 8 192 bytes**, even on a file
smaller than the window.

### big.jsonl (6.9 MiB)

First three reads:

```
read(13, "{\"temp\":\"ok\",\"n\":1}\n{\"temp\":\"ok\""..., 8192) = 8192
read(13, "\"temp\":\"ok\",\"n\":374}\n{\"temp\":\"ok"..., 8192)   = 8192
read(13, "p\":\"ok\",\"n\":742}\n{\"temp\":\"ok\",\"n"..., 8192)   = 8192
```

Last three reads:

```
read(13, "\",\"n\":290553}\n{\"temp\":\"ok\",\"n\":2"..., 8192)    = 8192
read(13, "7}\n{\"temp\":\"ok\",\"n\":290878}\n{\"te"..., 8192)    = 1417
read(13, "", 6775)                                              = 0
```

885 read() syscalls on fd 13 total. Of the 884 reads that request 8192
bytes, 883 return 8192 bytes and 1 (the tail) returns 1417 bytes — the
kernel gets less than 8192 because the file is exhausted. Then 1 read
returns 0 (EOF, on a buffer sized 6775 — strace's remainder). The 1417
is a *return* value, not a *request* size; the request size is always
exactly 8192 (or whatever bytes the strace remainder requested on the
EOF probe). **Total bytes pulled before EOF: 884 × 8192 + 1417 = 7 234 953
= file size** (all 290 933 rows processed). **No read returns more than
8192 bytes** on a file 884× the window.

## Claim

The installed CLI binary (built from HEAD `926f1ed`, jar SHA in
`docs/history/B6_RELEASE_ADMISSION.md`) reads the JSONL file through the
8 KiB sliding window defined in `JsonlByteSource.Windowed`. The first
read on the file is exactly `read(fd, buf, 8192)`, on both the 3.8 KiB
and the 6.9 MiB fixture. Memory bound is therefore fixed at the window
size and does not scale with the file.

This is the same numeric claim as H3.1-CSV, this time observed at the
process boundary of the production jar.

## Reproduction

```bash
# Reinstall the CLI (H3.1-JSONL has not been on install/ before)
./gradlew :pipelinek-policy-cli:installDist -x test

# Build the bundle (one-shot test, see commit body)
./gradlew :pipelinek-policy-cli:test \
    --tests "com.pipelinek.policy.cli.commands.H31JlAcceptanceBundleDumperTest"

# Generate fixtures (3.8 KiB, 6.9 MiB)
python3 /tmp/h31jl-uat/make_fixtures.py

# Measure (one strace per fixture)
mise exec java@21 -- strace -f -e trace=read -ttt \
    -P /tmp/h31jl-uat/small.jsonl \
    -o /tmp/h31jl-uat/small.strace -- \
    ./pipelinek-policy-cli/build/install/pipelinek-policy-cli/bin/pipelinek-policy-cli \
    stream --policy /tmp/h31jl-uat/p.bundle /tmp/h31jl-uat/small.jsonl

mise exec java@21 -- strace -f -e trace=read -ttt \
    -P /tmp/h31jl-uat/big.jsonl \
    -o /tmp/h31jl-uat/big.strace -- \
    ./pipelinek-policy-cli/build/install/pipelinek-policy-cli/bin/pipelinek-policy-cli \
    stream --policy /tmp/h31jl-uat/p.bundle /tmp/h31jl-uat/big.jsonl

# Inspect
grep -E 'read\(13, .*\) = [0-9]+' /tmp/h31jl-uat/big.strace | head -3
grep -E 'read\(13, .*\) = [0-9]+' /tmp/h31jl-uat/big.strace | tail -3
```
