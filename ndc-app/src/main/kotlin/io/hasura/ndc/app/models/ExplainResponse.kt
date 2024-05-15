package io.hasura.ndc.app.models

data class ExplainResponse(
    val lines: List<String>? = emptyList(),
    val query: String
)
