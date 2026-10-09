# ADR-0012 — CLI exit-code contract

- Status: ACCEPTED (B3)
- Cycle: p-dd1a1c7a7d448b0c/m8-datasets-streaming
- Date: 2026-10-09
- Deciders: orchestrator on behalf of pipelinek-policy
- Source spec: `docs/03-specifications/CLI_AND_AGENT_SURFACE.md` §9; ROADMAP B3.

## Context

The CLI exposed contradictory status codes: policy violations used 1, usage,
bundle refusal, and decoder refusal shared 2, and several commands returned
success after a failed decision. Machine callers could not distinguish a policy
denial from malformed input or an evaluator error. `test` also treated a typed
`RuleEvaluation.Error` as a deny because the error's primary diagnostic is
exposed in its `violations` collection.

The existing specification reserved 0 and 2 through 5, and explicitly rejected
using 1 as a generic failure code. It did not assign 1 to a distinct category.

## Decision

### D12.1 — Stable, disjoint exit-code matrix

| Code | Meaning |
|---:|---|
| 0 | Command completed successfully; a policy decision permits |
| 1 | CLI invocation or usage error only |
| 2 | Policy violation or blocking non-empty policy diff |
| 3 | Evaluation, configuration, plan, budget, or runtime error |
| 4 | Bundle/resource admission or decoding refusal |
| 5 | Canonical policy IR compilation error |

Code 1 is not a generic error code. Missing required arguments, unknown
commands/operations, invalid option values, and a fixture directory with no
recognized fixtures are invocation failures. Invalid input content is
classified by its boundary instead: admission/refusal is 4, IR compilation is
5, and runtime/evaluation failure is 3.

### D12.2 — Aggregate precedence and fixture semantics

When a command evaluates multiple resources, the most severe observed outcome
wins in this order: admission/refusal (4), evaluation/runtime error (3), policy
violation (2), success (0). Invocation errors return 1 before evaluation.

`RuleEvaluation.Error` is never a policy denial. A `.deny.` fixture passes only
when evaluation completed and produced a `RuleEvaluation.Violated` result. A
typed evaluator error returns 3, regardless of the fixture's expected result.

### D12.3 — Public help and command behavior share the matrix

Root and command-level `--json-help`, documentation, and `ExitCodes` expose the
same 0..5 meanings. Command handlers classify failures by the boundary that
rejected the operation rather than collapsing admission and invocation errors.

## Consequences

- This intentionally changes the earlier M9 mapping: violation moves from 1 to
  2, invocation error from 2 to 1, and admission refusal gets its own code 4.
- Agents can distinguish a rejected policy from evaluator/configuration faults,
  corrupt bundles, unsupported resources, and compiler failures without parsing
  human-oriented diagnostics.
- `test`, `explain`, `check`, `stream`, `compile`, `bundle verify`, `inspect`,
  `shape`, and `diff` must keep their public exit behavior and machine help in
  sync with this matrix.
- Empty or unrecognized fixture directories and invalid stream options cannot
  silently become successful runs or implicit defaults.
