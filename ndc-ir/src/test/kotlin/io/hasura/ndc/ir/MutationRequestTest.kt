package io.hasura.ndc.ir

import org.junit.jupiter.api.Test

class MutationRequestTest {

    @Test
    fun parseMutationRequests() {
        RequestParser.parseRequests("/mutationRequests", MutationRequest::class.java)
    }
}
