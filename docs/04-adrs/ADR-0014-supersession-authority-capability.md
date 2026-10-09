# ADR-0014 — Supersession authority is a granted capability, not a claim

- Status: Accepted
- WorkItem: B4-T2 (ROADMAP.md §B4.1)
- Supersedes: nothing; makes real the follow-up noted in `Layers.kt` since M7

## Context

`Supersession` lets a lower-authority layer replace a rule from a
higher-authority one. That is architectural law 12 in practice: it is the one
mechanism by which a lower layer can legitimately weaken an upper one, so it
is exactly the mechanism that must be trustworthy.

Until B4-T2 the only field standing between a bundle and an upper-layer
override was:

```kotlin
val authority: String
// require(authority.isNotBlank())
```

`LayerComposer.placementOf` decided `Replaced` purely on
`supersession.supersedes == RuleRef(...)`. The `authority` string was checked
for non-blankness and then never read. The consequence: **any bundle could
replace any upper-layer rule by writing any non-blank string into the field
named `authority`.** The field's name asserted a check that did not exist.

## Decision

1. `Supersession.authority` becomes `SupersessionAuthority(issuer,
   grantedLayers, grantDigest)` — a typed CLAIM. It records who the bundle
   says authorized the weakening and over which layers.

2. Grants live in `AuthorityRegistry`, an ordinary value passed **by
   parameter** to `LayerComposer.compose(layers, authorities =
   AuthorityRegistry.EMPTY)`. Architectural law 10 forbids a global mutable
   registry, so the registry is data, not ambient state.

3. `AuthorityRegistry.EMPTY` is the default and grants nothing. A caller that
   supplies no registry is not silently trusted; it is refused.

4. A supersession is authorized only when the registry holds a grant for the
   claimed issuer AND that grant covers every layer the claim names AND the
   incoming layer is among them. A claim wider than the grant is REFUSED, not
   clamped. Otherwise a bundle could widen its own authority by describing
   itself accurately.

5. `grantDigest` is load-bearing, not decorative. `AuthorityRegistry` stores
   the full `SupersessionAuthority` per issuer, and `covers()` requires the
   claim's digest to MATCH the host's. The same issuer with a different digest
   is a different grant.

   This was corrected in a follow-up commit after review. The field shipped
   first with a KDoc promising a cryptographic binding to the real grant while
   no code compared it — the exact "field that appears to guarantee what it
   does not guarantee" pattern the roadmap forbids. It was worse than absence:
   a maintainer reading the KDoc would reasonably assume it was verified.
   The alternative (deleting the field) was considered and rejected; binding it
   is available without adding any dependency, because the kernel never
   COMPUTES a digest. The host computes it and the kernel compares two strings,
   so core stays `kotlin-stdlib`-only per ADR-0011 D11.1.

6. Failure is a typed refusal, `LayerCompositionRefusal.AuthorityLacksCapability`,
   carrying the issuer, the layer, what the host granted, what the bundle
   claimed, and both digests so a mismatch is diagnosable rather than opaque.
   Never a silent drop, never a throw at the call site.

## Consequences

**A bundle may claim a grant; only the host may grant one.** The registry is
host-supplied and is never read from a bundle. A grant assembled from a
bundle's own `metadata.txt` is the claim again under a different name, which
is why the decoder refuses to fabricate one.

**Existing compositions that relied on textual authority now refuse.** This is
the intended behavior change. `LayersTest.02a` was updated to pass a registry,
because it asserts `Composed`; it was not weakened to assert `Refused`.

**Wire format.** `authority` moves from a JSON string to an object
`{issuer, grantedLayers, grantDigest}`. The decoder REFUSES the legacy bare
string with `IrRefusal.CorruptEncoding` rather than coercing it: a bare string
names no issuer, no layers and no digest, and inventing them would fabricate a
grant out of the very field that was previously trusted unconditionally.

The B2 legacy v1 golden digest is unaffected — verified, not assumed:
`src/test/resources/ir/legacy-v1-source-ref.json` contains zero `supersession`
blocks, so no golden document encodes an authority. `PolicyBundleAdmissionFortressTest`
(7 tests) and `CanonicalPolicyJsonFortressTest` (5 tests) both remain green.

## Alternatives rejected

- **Keep the String and check it against a list of known issuers.** Rejected:
  an allowlist of names is not a grant. Anyone who can write a bundle can write
  a name on the list. It re-creates the defect with extra steps.
- **Read the registry from the bundle's `metadata.txt`.** Rejected: the bundle
  is untrusted input. This is self-granting.
- **Default the parameter to a permissive registry for backward
  compatibility.** Rejected: the default is the path every caller takes until
  a host explicitly opts in, so a permissive default silently restores the
  defect for exactly the callers nobody audits.
