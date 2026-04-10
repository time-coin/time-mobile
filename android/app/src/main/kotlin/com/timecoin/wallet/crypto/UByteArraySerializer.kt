package com.timecoin.wallet.crypto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Serializes ByteArray as a JSON array of unsigned integers (0–255).
 *
 * Kotlin's default ByteArray serializer emits signed values (-128..127),
 * which Rust's serde rejects when deserializing into [u8]. This serializer
 * converts each byte to its unsigned representation before encoding.
 */
object UByteArraySerializer : KSerializer<ByteArray> {
    private val delegate = ListSerializer(Int.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: ByteArray) {
        delegate.serialize(encoder, value.map { it.toInt() and 0xFF })
    }

    override fun deserialize(decoder: Decoder): ByteArray {
        return delegate.deserialize(decoder).map { it.toByte() }.toByteArray()
    }
}
