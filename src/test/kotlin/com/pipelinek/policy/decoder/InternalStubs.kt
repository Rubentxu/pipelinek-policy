package com.pipelinek.policy.decoder

import com.pipelinek.policy.kernel.value.ResourceId
import com.pipelinek.policy.kernel.value.ValueNode

/**
 * Test-scope helpers used by `DecoderSpiContractTest`. Production code MUST
 * NOT depend on this file — it lives under `src/test/...` so the production
 * SPI keeps zero parser coords and zero stub footprint.
 */
internal object InternalStubs {

    /**
     * Returns a stub `ResourceDecoder` whose `decode()` always returns
     * `Refused(MALFORMED, anchor = null)` without throwing. Useful for
     * proving the SPI is total in tests that have no parser on the
     * classpath.
     */
    fun alwaysRefusesDecoder(): ResourceDecoder = object : ResourceDecoder {
        override val descriptor: DecoderDescriptor = DecoderDescriptor(
            format = ResourceFormat.MAP_ADAPTER,
            version = "stub-1.0.0-m2",
        )

        override fun decode(
            bytes: ByteArray,
            options: DecodeOptions,
        ): DecodeResult = DecodeResult.Refused(DecodeRefusal(DecodeRefusalCode.MALFORMED, anchor = null))
    }

    /** Returns a stub `ResourceDecoder` whose `decode()` always returns `Ok`. */
    fun alwaysOkDecoder(): ResourceDecoder = object : ResourceDecoder {
        override val descriptor: DecoderDescriptor = DecoderDescriptor(
            format = ResourceFormat.MAP_ADAPTER,
            version = "stub-1.0.0-m2",
        )

        override fun decode(
            bytes: ByteArray,
            options: DecodeOptions,
        ): DecodeResult = DecodeResult.Ok(
            listOf(
                ResourceDocument(
                    id = ResourceId("stub"),
                    format = ResourceFormat.MAP_ADAPTER,
                    root = ValueNode.Null,
                    sourceMap = SourceMap.EMPTY,
                ),
            ),
        )
    }

    fun contributor(decoders: List<ResourceDecoder>): DecoderContributor =
        object : DecoderContributor {
            override val decoders: List<ResourceDecoder> = decoders
        }
}
