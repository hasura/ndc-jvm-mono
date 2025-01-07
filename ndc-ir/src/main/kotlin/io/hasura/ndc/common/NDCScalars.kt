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
    JSON;

    @JsonValue
    fun toValue(): String = name.lowercase()
}

fun javaSqlTypeToNDCScalar(sqlType: Int): NDCScalar {
    return when (sqlType) {
        // Boolean Types
        java.sql.Types.BIT, 
        java.sql.Types.BOOLEAN -> NDCScalar.BOOLEAN

        // Integer Types
        java.sql.Types.TINYINT -> NDCScalar.INT8
        java.sql.Types.SMALLINT -> NDCScalar.INT16
        java.sql.Types.INTEGER -> NDCScalar.INT32
        java.sql.Types.BIGINT -> NDCScalar.INT64

        // Floating-Point Types
        java.sql.Types.FLOAT, 
        java.sql.Types.REAL -> NDCScalar.FLOAT32
        java.sql.Types.DOUBLE -> NDCScalar.FLOAT64
        java.sql.Types.NUMERIC, 
        java.sql.Types.DECIMAL -> NDCScalar.BIGDECIMAL

        // String Types
        java.sql.Types.CHAR, 
        java.sql.Types.VARCHAR, 
        java.sql.Types.LONGVARCHAR, 
        java.sql.Types.NCHAR, 
        java.sql.Types.NVARCHAR, 
        java.sql.Types.LONGNVARCHAR -> NDCScalar.STRING

        // Date and Time Types
        java.sql.Types.DATE -> NDCScalar.DATE
        java.sql.Types.TIME -> NDCScalar.TIMESTAMP
        java.sql.Types.TIME_WITH_TIMEZONE -> NDCScalar.TIMESTAMPTZ
        java.sql.Types.TIMESTAMP -> NDCScalar.TIMESTAMP
        java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> NDCScalar.TIMESTAMPTZ

        // Binary Types
        java.sql.Types.BINARY, 
        java.sql.Types.VARBINARY, 
        java.sql.Types.LONGVARBINARY -> NDCScalar.BYTES

        // JSON Types
        java.sql.Types.JAVA_OBJECT, 
        java.sql.Types.SQLXML, 
        java.sql.Types.OTHER,
        java.sql.Types.STRUCT -> NDCScalar.JSON // Default to JSON, specific mappings need additional metadata

        // Default Fallback
        else -> NDCScalar.JSON
    }
}

fun javaSqlTypeToNDCScalar(sqlType: Int, precision: Int? = null, scale: Int? = null): NDCScalar {
    return when (sqlType) {
        // Boolean Types
        java.sql.Types.BIT, 
        java.sql.Types.BOOLEAN -> NDCScalar.BOOLEAN

        // Integer Types
        java.sql.Types.TINYINT -> NDCScalar.INT8
        java.sql.Types.SMALLINT -> NDCScalar.INT16
        java.sql.Types.INTEGER -> NDCScalar.INT32
        java.sql.Types.BIGINT -> NDCScalar.INT64

        // Numeric Types
        java.sql.Types.NUMERIC, 
        java.sql.Types.DECIMAL -> {
            if (scale == 0) { // Integer-like NUMERIC/DECIMAL
                if (precision != null && precision > 19) NDCScalar.BIGINTEGER // Precision >19 indicates BigInteger
                else NDCScalar.INT64
            } else {
                NDCScalar.BIGDECIMAL
            }
        }

        // Floating-Point Types
        java.sql.Types.FLOAT, 
        java.sql.Types.REAL -> NDCScalar.FLOAT32
        java.sql.Types.DOUBLE -> NDCScalar.FLOAT64

        // String Types
        java.sql.Types.CHAR, 
        java.sql.Types.VARCHAR, 
        java.sql.Types.LONGVARCHAR, 
        java.sql.Types.NCHAR, 
        java.sql.Types.NVARCHAR, 
        java.sql.Types.LONGNVARCHAR -> NDCScalar.STRING

        // Date and Time Types
        java.sql.Types.DATE -> NDCScalar.DATE
        java.sql.Types.TIME -> NDCScalar.TIMESTAMP
        java.sql.Types.TIME_WITH_TIMEZONE -> NDCScalar.TIMESTAMPTZ
        java.sql.Types.TIMESTAMP -> NDCScalar.TIMESTAMP
        java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> NDCScalar.TIMESTAMPTZ

        // Binary Types
        java.sql.Types.BINARY, 
        java.sql.Types.VARBINARY, 
        java.sql.Types.LONGVARBINARY -> NDCScalar.BYTES

        // JSON Types
        java.sql.Types.JAVA_OBJECT, 
        java.sql.Types.SQLXML, 
        java.sql.Types.OTHER,
        java.sql.Types.STRUCT -> NDCScalar.JSON

        // Default Fallback
        else -> NDCScalar.JSON
    }
}