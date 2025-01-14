package io.hasura.ndc.common

import com.fasterxml.jackson.annotation.JsonValue

enum class NDCScalar {
    BOOLEAN,
    STRING,
    INT8,
    INT16,
    INT32,
    INT64,
    FLOAT32,
    FLOAT64,
    BIGINTEGER,
    BIGDECIMAL,
    UUID,
    DATE,
    TIMESTAMP,
    TIMESTAMPTZ,
    GEOGRAPHY,
    GEOMETRY,
    BYTES,
    VECTOR,
    JSON;

    @JsonValue
    fun toValue(): String = name.lowercase()
}
