# H2.2 · Installed-CLI acceptance evidence for the GLOBAL refusal path

**Cycle:** closing the H2.2 reviewer-flagged gap.
**Verdict: PASS (OBSERVED)** — exercised through the installed CLI binary,
not just the test JVM.

## What was missing

The H2.2 row on `ROADMAP.md` says

> the planner's return is `DatasetPlanner.PlanResult` (sealed), with
> `DatasetPlan` and `PlanRefusal` as the two members ... Three tests in
> `H22SealedPlanTest` (sealed return, refusal in the same type, absence of
> `InMemoryIndex` decorative).

The three tests are in-test-JVM. The reviewer (and the user) requires the
real CLI binary to surface the same refusal at the agent-facing surface:
exit code, stdout text, JSON shape, and the rule the refusal names.

## Method

Built a bundle from the same `PolicySet` construction
`H22SealedPlanTest.02` uses — a single rule whose expression is a
free-floating `Expression.DatasetRef("telemetry")` — and ran the installed
CLI's `stream` command against it with a real CSV dataset.

Bundle dumper: `H22AcceptanceBundleDumperTest` (not committed; same
one-shot pattern as the H3.1-JSONL bundle dumper). The dumper was added,
executed once via `:pipelinek-policy-cli:test --tests ...`, and removed;
the bundle is the only durable artifact under `/tmp/h22-uat/`.

## Observed (text format)

```
$ ./pipelinek-policy-cli stream --policy /tmp/h22-uat/p.bundle /tmp/h22-uat/data.csv
stream: plan-refusal: plan refused (RuleKey(policySetId=s, policyId=p, ruleId=cross)):
GLOBAL access requires an index plan; the kernel refuses unbounded cross-row
evaluation (law 12). Declare requiresGlobalIndex or reshape the rule.
$ echo $?
3
```

## Observed (JSON format)

```
$ ./pipelinek-policy-cli stream --policy /tmp/h22-uat/p.bundle \
    --format-out json /tmp/h22-uat/data.csv
{"kind":"plan-refusal","message":"plan refused (RuleKey(policySetId=s, policyId=p, ruleId=cross)): GLOBAL access requires an index plan; the kernel refuses unbounded cross-row evaluation (law 12). Declare requiresGlobalIndex or reshape the rule."}
$ echo $?
3
```

## Acceptance

| Item                                          | Expected                            | Observed |
|-----------------------------------------------|-------------------------------------|----------|
| Exit code                                     | 3 (EVALUATION_ERROR)                | 3        |
| `kind` in JSON                                | `plan-refusal`                      | `plan-refusal` |
| Names the offending rule                      | `policySetId`, `policyId`, `ruleId` | `s`, `p`, `cross` |
| Names the architectural reason                | `law 12`                            | `law 12` |
| Names the path to fix                         | `Declare requiresGlobalIndex or reshape the rule` | same |
| Text-format prefix                            | `stream: `                          | `stream: ` |

The H2.2 row's claim — "the planner's return is sealed and a free-floating
`Expression.DatasetRef` produces a `PlanRefusal`" — is now backed by an
exercised path through the production jar, not only the test JVM.

## Reproduction

```bash
# Bundle (one-shot, dumper test added+executed+removed)
./gradlew :pipelinek-policy-cli:test \
    --tests "com.pipelinek.policy.cli.commands.H22AcceptanceBundleDumperTest"

# Run
./pipelinek-policy-cli/build/install/pipelinek-policy-cli/bin/pipelinek-policy-cli \
    stream --policy /tmp/h22-uat/p.bundle /tmp/h22-uat/data.csv
echo $?  # expect 3
```
