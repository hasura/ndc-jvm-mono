package io.hasura.ndc.common

enum class NDCScalar {
    INT,
    FLOAT,
    BOOLEAN,
    STRING,
    DATETIME,
    DATE,
    TIME,
    TIME_WITH_TIMEZONE,
    DATETIME_WITH_TIMEZONE
}

fun javaSqlTypeToNDCScalar(sqlType: Int): NDCScalar {
    return when (sqlType) {
        java.sql.Types.BIT -> NDCScalar.BOOLEAN
        java.sql.Types.BOOLEAN -> NDCScalar.BOOLEAN

        java.sql.Types.TINYINT -> NDCScalar.INT
        java.sql.Types.SMALLINT -> NDCScalar.INT
        java.sql.Types.INTEGER -> NDCScalar.INT
        java.sql.Types.BIGINT -> NDCScalar.INT

        java.sql.Types.FLOAT -> NDCScalar.FLOAT
        java.sql.Types.REAL -> NDCScalar.FLOAT
        java.sql.Types.DOUBLE -> NDCScalar.FLOAT
        java.sql.Types.NUMERIC -> NDCScalar.FLOAT
        java.sql.Types.DECIMAL -> NDCScalar.FLOAT

        java.sql.Types.CHAR -> NDCScalar.STRING
        java.sql.Types.VARCHAR -> NDCScalar.STRING
        java.sql.Types.LONGVARCHAR -> NDCScalar.STRING
        java.sql.Types.NCHAR -> NDCScalar.STRING
        java.sql.Types.NVARCHAR -> NDCScalar.STRING
        java.sql.Types.LONGNVARCHAR -> NDCScalar.STRING

        java.sql.Types.DATE -> NDCScalar.DATE
        java.sql.Types.TIME -> NDCScalar.TIME
        java.sql.Types.TIME_WITH_TIMEZONE -> NDCScalar.TIME_WITH_TIMEZONE
        java.sql.Types.TIMESTAMP -> NDCScalar.DATETIME
        java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> NDCScalar.DATETIME_WITH_TIMEZONE

        java.sql.Types.OTHER -> NDCScalar.STRING
        else -> NDCScalar.STRING
    }
}
