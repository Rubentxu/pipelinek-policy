# AGENTS.md — template para pipelinek-policy

## Operating mode

- SDDK is mandatory.
- Read root `ROADMAP.md` before selecting work.
- Root `ROADMAP.md` is the only sequencing authority.
- Do not store SDDK/runtime pipeline state in the repository.
- Use surgical tests during implementation; full suite only at integration/release gates.
- Use atomic Conventional Commits.
- “Done” means acceptance criteria and required UAT/AAT are demonstrated.

## Architectural laws

1. `ValueTree` is the runtime structured-data authority.
2. `PolicyIR` is the executable policy authority.
3. FIR/Kotlin authoring sugar MUST lower to canonical explicit semantics and pass parity tests.
4. Policy runtime MUST NOT execute arbitrary author JVM bytecode.
5. Evaluator core MUST NOT perform filesystem/network/process/credential/clock/random I/O.
6. Format-specific libraries MUST NOT be dependencies of policy-domain/evaluator.
7. `Map<String, Any?>` MUST NOT leak into public domain contracts.
8. Missing, Null and TypeMismatch MUST remain distinct.
9. No silent coercion.
10. No global mutable registries.
11. PipelineK integration MUST use the public external-plugin seam; no plugin-specific coordinator branch.
12. Lower policy layers MUST NOT silently weaken mandatory upper-layer rules.

## Compiler plugin rule

Kotlin compiler/FIR integration is DX, not semantic authority. Before changing FIR lowering, update/execute explicit-vs-sugar canonical IR parity tests.

## Falsification

For semantic fixes/features add at least one mutation/negative test that would have passed before the fix and fails under the targeted mutation.
