package common

sealed interface JsonValue {
    data object Null : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data class NumberValue(val value: String) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class ArrayValue(val values: List<JsonValue>) : JsonValue
    data class ObjectValue(val values: Map<String, JsonValue>) : JsonValue
}

typealias JsonContainer = Map<String, JsonValue>
