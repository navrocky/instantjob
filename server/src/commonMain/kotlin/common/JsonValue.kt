package common

/** Framework-independent representation of a JSON-compatible value. */
sealed interface JsonValue {
    /** JSON null value. */
    data object Null : JsonValue

    /** Represents a Boolean JSON value. */
    data class BooleanValue(
        /** Boolean value. */
        val value: Boolean,
    ) : JsonValue

    /** Represents a JSON number without losing its original precision. */
    data class NumberValue(
        /** Exact textual representation of the number. */
        val value: String,
    ) : JsonValue

    /** Represents a string JSON value. */
    data class StringValue(
        /** String value. */
        val value: String,
    ) : JsonValue

    /** Represents a JSON array. */
    data class ArrayValue(
        /** Ordered elements of the array. */
        val values: List<JsonValue>,
    ) : JsonValue

    /** Represents a JSON object. */
    data class ObjectValue(
        /** Properties of the object indexed by name. */
        val values: Map<String, JsonValue>,
    ) : JsonValue
}

/** JSON object used for task parameters and execution results. */
typealias JsonContainer = Map<String, JsonValue>
