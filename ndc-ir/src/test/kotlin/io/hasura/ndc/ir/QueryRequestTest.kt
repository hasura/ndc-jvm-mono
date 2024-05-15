package io.hasura.ndc.ir

import org.junit.jupiter.api.Test

class QueryRequestTest {

    @Test
    fun parseQueryRequests() {
        RequestParser.parseRequests("/queryRequests", QueryRequest::class.java)
    }
}
