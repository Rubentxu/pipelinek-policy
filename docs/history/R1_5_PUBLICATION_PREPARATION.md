# R1.5 — Publication preparation for v0.3.0-alpha.2

## Authorization and measured candidate

- User explicitly authorized push, PR creation/merge, annotated tag `v0.3.0-alpha.2`, GitHub prerelease and download/SHA-256 verification at `2026-10-11T22:22:26.808Z`.
- Preflight repository/account: `Rubentxu/pipelinek-policy` / `Rubentxu`.
- Remote main was `c906988582ea16085bb6ec0154559e5a30c770c6`, with no divergent remote commits and no alpha.2 tag or release.
- Measured prepublication candidate: `1b2e923bd050f66299ef67799c72d0a0ce44a1c6` on `integration/r1-alpha.2`.

## Fresh gates, not aggregate assumptions

- Initial certify on that candidate: 623 executed, 0 failures/errors/skipped.
- The first mutation attempt was interrupted by the tool's 600-second limit at K1. **INTERRUPTED, not PASS.** It left exactly the K1 `Waivers.kt` predicate mutation. The diff was checked against the script and that file was restored to HEAD before retrying.
- Complete retry: **38 killed / 0 survived / 0 invalid**, exit 0. The gate reports that every mutant compiled, died by assertion and restored green. The full successful output is `B6_MUTATION_RECEIPT.log`. The observer timeout did not terminate the independently running second attempt.
- After restoration, source diffs were empty. `gradle-jdk21.sh assemble :pipelinek-policy-cli:distZip --rerun-tasks` completed successfully.
- Post-mutation certify ran again with fresh XML: **623/0/0/0**, JVM 21, Kotlin 2.4.20, SDK 0.47.0. Root source SHA remains `d5f2c2cb7bbc9b901aba0d29c907d952f6fda8ea` over 91 files. See `B6_CERTIFICATION.md`.
- These measurements do not yet claim publication, merge, tag or downloaded-asset verification. Those are postmerge/postpublication checks.

## Real CLI ZIP acceptance

The CLI ZIP was extracted to an isolated scratch directory and its own launcher invoked through the JDK21 wrapper against the existing H2.2 bundle and CSV. This is not a help-only smoke test.

```text
stream --policy p.bundle --format-out json data.csv
exit: 3
kind: plan-refusal
message names: policySetId=s, policyId=p, ruleId=cross
message reason: GLOBAL access requires an index plan
```

`jq` checked the kind, architectural reason and offending rule. Observed input hashes:

```text
ca5edc97dd4527fbee1687598fe9dc844839460e3ee9ff50f50c5c47a89a2fc3  p.bundle
6f09e8af3cc5a9978d6924722e59b6b18664dcb8ad32b4589dcd3a0451a1fc77  data.csv
```

Premerge CLI ZIP hash: `e5645021d45f2702305105d0884f8187ec331a28a2ed21c4e64abd61ffc5c1ef`. Postmerge bytes will be rebuilt, staged, measured and verified independently.

## Packaging defects found during independent review

1. `release-admission.sh:151-155` excludes all versioned jars, including the current root jar. Its eight selected jars alone are not a complete modular/CLI distribution. The CLI is a thin application jar and depends on root plus third-party runtime libraries.
2. `certify.sh:160` counts directories in the global Gradle cache. Its historical `622 coordinates` field is **not** resolved-dependency coverage of this project.
3. The generated SPDX JSON enumerates only selected jar artifacts and validates JSON syntax. It is not a dependency SBOM and no full SPDX schema validation is demonstrated.
4. Admission's repo-relative checksum paths are unsuitable for flat release downloads. It hashes jars, not the inventory JSON.
5. Admission is not a freshness proof of artifact bytes. A fresh integrated rebuild and immutable staging are required as operational safeguards.

## Narrow publication remedy, no gate weakening

- Keep the existing admission gate unchanged. Record its eight-artifact verdict as its actual scope, not as complete CLI packaging.
- Additionally publish the current root jar and a real CLI `distZip`, rebuilt on the integrated tree. The latter carries its runtime libraries.
- Identify `pipelinek-policy-plugin-all.jar` as the plugin installable artifact. Thin jars and the parity/test-harness jar are supplementary build artifacts, not standalone executables.
- Publish the SPDX JSON only as a limited artifact inventory. Correct its misleading generated creation comment in the staged copy, preserving the raw gate output as evidence. State that the root jar/CLI ZIP supplement is outside the gate inventory.
- Stage immutable files with unique basenames. Generate a flat SHA256SUMS over all downloadable payloads, including the inventory JSON, and compare the eight jar digests against admission output.
- After merge, re-run certify and admission on the exact integrated SHA. Check ancestry, clean tree and absence of source changes explicitly. The release tag must peel to that integrated SHA and equal remote main at tagging time.
- Download every published asset. Compare the downloaded checksum manifest with the local staged manifest and run `sha256sum -c`; re-exercise the CLI from the downloaded ZIP with the same real fixture.

## Known alpha limitations remain

Root-only certification scope includes root main and test, but excludes module sources. Provenance is `NOT_ATTESTED`. Installed-host policy.check and replay hit/miss remain `NOT_MEASURED`. R3.1's JVM ServiceLoader probe is not a complete PipelineK host acceptance test. M4 FAIL and M8 closure restrictions are not changed by publication. This prerelease is not production-ready certification.
