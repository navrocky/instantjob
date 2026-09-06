package infrastructure.database

import common.JsonContainer
import common.JsonValue
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull

@OptIn(ExperimentalSerializationApi::class)
internal val databaseJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

@OptIn(ExperimentalSerializationApi::class)
internal fun JsonContainer.toJsonObject(): JsonObject = JsonObject(mapValues { it.value.toJsonElement() })

internal fun JsonObject.toJsonContainer(): JsonContainer = mapValues { it.value.toJsonValue() }

@OptIn(ExperimentalSerializationApi::class)
private fun JsonValue.toJsonElement(): JsonElement = when (this) {
    JsonValue.Null -> JsonNull
    is JsonValue.BooleanValue -> JsonPrimitive(value)
    is JsonValue.NumberValue -> JsonUnquotedLiteral(value)
    is JsonValue.StringValue -> JsonPrimitive(value)
    is JsonValue.ArrayValue -> JsonArray(values.map { it.toJsonElement() })
    is JsonValue.ObjectValue -> JsonObject(values.mapValues { it.value.toJsonElement() })
}

private fun JsonElement.toJsonValue(): JsonValue = when (this) {
    JsonNull -> JsonValue.Null
    is JsonObject -> JsonValue.ObjectValue(toJsonContainer())
    is JsonArray -> JsonValue.ArrayValue(map { it.toJsonValue() })
    is JsonPrimitive -> when {
        isString -> JsonValue.StringValue(content)
        booleanOrNull != null -> JsonValue.BooleanValue(boolean)
        else -> JsonValue.NumberValue(content)
    }
}
