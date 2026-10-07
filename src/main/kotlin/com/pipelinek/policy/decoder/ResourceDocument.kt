package com.pipelinek.policy.decoder

import com.pipelinek.policy.kernel.value.ResourceId
import com.pipelinek.policy.kernel.value.ValueNode

/**
 * Spec REQ §ADDED REQ 1 — physical format the bytes were decoded from.
 * Adding a new format means extending this enum AND registering a new
 * `policy-decoders-<format>` submodule; the enum is the contract surface
 * between the SPI and the parser submodules.
 */
enum class ResourceFormat { JSON, YAML, CSV, MAP_ADAPTER }

/**
 * Spec REQ §"SourceMap laws" §9 — non-semantic metadata a decoder MAY attach
 * (MIME type the caller claimed, file URL or synthetic key). Optional; both
 * fields default to `null` so adapters with no upstream context pay no cost.
 */
data class ResourceAttributes(
    val mediaType: String? = null,
    val originRef: String? = null,
)

/**
 * Spec REQ §ADDED REQ 1 — a fully decoded resource: the `root` `ValueTree`
 * plus the `sourceMap` carrier (sibling, never part of `canonicalDigest`).
 * Multi-document streams produce multiple `ResourceDocument`s in source order
 * (per ADR-0011 D-04 for YAML; JSON top-level arrays collapse to one doc).
 */
data class ResourceDocument(
    val id: ResourceId,
    val format: ResourceFormat,
    val root: ValueNode,
    val sourceMap: SourceMap,
    val attributes: ResourceAttributes = ResourceAttributes(),
)
