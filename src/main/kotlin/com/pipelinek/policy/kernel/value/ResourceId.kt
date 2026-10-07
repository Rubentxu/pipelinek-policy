package com.pipelinek.policy.kernel.value

/**
 * Stable identity for a logical resource (file, kubernetes object, adapter
 * input). The `ResourceId` is owned by the kernel's value package because it
 * participates in `ResourceDocument` and must round-trip through pure-stdlib
 * hashing (no format-specific transitive deps).
 *
 * Carriers (`SourceMap`, `SourceAnchor`) and formats (`JSON`, `YAML`, ...)
 * live under `com.pipelinek.policy.decoder`; this value class stays here so
 * that `kernel` consumers can refer to a document without naming a decoder.
 *
 * Spec REQ §ADDED REQ 1 (Decoder SPI contract) requires `ResourceDocument.id`
 * to be of this type. ADR-0011 D-01 keeps the type pure-stdlib.
 */
@JvmInline
value class ResourceId(val value: String) {
    init {
        require(value.isNotEmpty()) { "ResourceId value MUST NOT be empty (law: empty ids have no provenance)" }
    }
}
