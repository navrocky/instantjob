package common

/** Framework-independent representation of a JSON-compatible value. */
sealed interface JsonValue {
    /** JSON null value. */
    data object Null : JsonValue

    /** @property value Boolean JSON value. */
    data class BooleanValue(val value: Boolean) : JsonValue

    /** @property value Exact textual representation of a JSON number. */
    data class NumberValue(val value: String) : JsonValue

    /** @property value String JSON value. */
    data class StringValue(val value: String) : JsonValue

    /** @property values Ordered elements of a JSON array. */
    data class ArrayValue(val values: List<JsonValue>) : JsonValue

    /** @property values Properties of a JSON object indexed by name. */
    data class ObjectValue(val values: Map<String, JsonValue>) : JsonValue
}

/** JSON object used for task parameters and execution results. */
typealias JsonContainer = Map<String, JsonValue>
