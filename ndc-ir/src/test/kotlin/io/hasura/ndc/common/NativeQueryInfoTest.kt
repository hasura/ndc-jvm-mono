package io.hasura.ndc.common

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test


class NativeQueryInfoTest {

    val mapper = jacksonObjectMapper()

    @Test
    fun parseNQ() {
        val json = this::class.java.getResource("/NativeQuery.json").readText(Charsets.UTF_8)
        val nq = mapper.readValue<Map<String, NativeQueryInfo>>(json)
        println("NQ: $nq")
    }
}
