package com.streamvault.data.remote.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalSerializationApi::class)
object LenientIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int = decodePrimitive(decoder)?.toIntOrNull() ?: 0

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeInt(value)
    }
}

@OptIn(ExperimentalSerializationApi::class)
object LenientNullableIntSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientNullableInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int? = decodePrimitive(decoder)?.toIntOrNull()

    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeInt(value)
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
object LenientLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long = decodePrimitive(decoder)?.toLongOrNull() ?: 0L

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeLong(value)
    }
}

@OptIn(ExperimentalSerializationApi::class)
object LenientNullableLongSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientNullableLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long? = decodePrimitive(decoder)?.toLongOrNull()

    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeLong(value)
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
object LenientNullableBooleanSerializer : KSerializer<Boolean?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientNullableBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean? {
        return when (decodePrimitive(decoder)?.trim()?.lowercase()) {
            null, "" -> null
            "1", "true", "yes", "y", "on" -> true
            "0", "false", "no", "n", "off" -> false
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: Boolean?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeBoolean(value)
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
object LenientStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String = decodePrimitive(decoder)?.trim().orEmpty()

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
}

@OptIn(ExperimentalSerializationApi::class)
object LenientNullableStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientNullableString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? = decodePrimitive(decoder)?.trim()?.takeIf { it.isNotEmpty() }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value)
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
object LenientStringListSerializer : KSerializer<List<String>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientStringList", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): List<String> = decodeStringList(decoder).orEmpty()

    override fun serialize(encoder: Encoder, value: List<String>) {
        encoder.encodeString(value.joinToString(","))
    }
}

@OptIn(ExperimentalSerializationApi::class)
object LenientNullableStringListSerializer : KSerializer<List<String>?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientNullableStringList", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): List<String>? = decodeStringList(decoder)

    override fun serialize(encoder: Encoder, value: List<String>?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value.joinToString(","))
        }
    }
}

private fun decodePrimitive(decoder: Decoder): String? {
    return when (decoder) {
        is JsonDecoder -> decoder.decodeJsonElement().primitiveContentOrNull()
        else -> runCatching { decoder.decodeString() }.getOrNull()
    }
}

private fun JsonElement.primitiveContentOrNull(): String? {
    if (this == JsonNull) return null
    val primitive = this as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.content else primitive.toString()
}

private fun decodeStringList(decoder: Decoder): List<String>? {
    return when (decoder) {
        is JsonDecoder -> decoder.decodeJsonElement().stringListOrNull()
        else -> runCatching { decoder.decodeString() }.getOrNull()?.toLenientStringList()
    }
}

private fun JsonElement.stringListOrNull(): List<String>? {
    return when (this) {
        JsonNull -> null
        is JsonPrimitive -> this.primitiveContentOrNull()?.toLenientStringList()
        is JsonArray -> this.flatMap { element -> element.stringListOrNull().orEmpty() }.normalizeStringList()
        is JsonObject -> this.values.flatMap { element -> element.stringListOrNull().orEmpty() }.normalizeStringList()
        else -> null
    }
}

private fun String.toLenientStringList(): List<String> {
    val normalized = trim()
    if (normalized.isEmpty() || normalized.equals("null", ignoreCase = true)) {
        return emptyList()
    }
    val unwrapped = normalized.removePrefix("[").removeSuffix("]")
    val splitRegex = Regex("""\s*(?:,|\|)\s*""")
    return unwrapped
        .split(splitRegex)
        .map { token -> token.trim().trim('"', '\'') }
        .filter { token -> token.isNotEmpty() && !token.equals("null", ignoreCase = true) }
        .ifEmpty { listOf(normalized.trim('"', '\'')) }
}

private fun List<String>.normalizeStringList(): List<String> =
    map { it.trim().trim('"', '\'') }
        .filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
