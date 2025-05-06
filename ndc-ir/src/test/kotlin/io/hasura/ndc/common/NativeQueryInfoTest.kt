package io.hasura.ndc.common

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail


class NativeQueryInfoTest {

    val mapper = jacksonObjectMapper()

    @Test
    fun parseInlineNativeQuery() {
        val json = this::class.java.getResource("/NativeQuery.json").readText(Charsets.UTF_8)
        val nq = mapper.readValue<Map<String, NativeQueryInfo>>(json)
        val artistByIdNativeQuery = nq["ArtistById"]

        if (artistByIdNativeQuery == null) {
            fail("Couldn't find ArtistById native query")
        } else {
            when (val x = artistByIdNativeQuery.sql) {
                is NativeQuerySql.Inline -> assert(x.sql == "SELECT * FROM \"Artist\" WHERE \"ArtistId\" < {{id}}")
                else -> fail("ArtistById native query is supposed to be inline")
            }


        }

    }

    @Test
    fun parseNativeQueryFromParts() {
        val json = this::class.java.getResource("/NativeQueryFromParts.json").readText(Charsets.UTF_8)
        val nq = mapper.readValue<Map<String, NativeQueryInfo>>(json)
        val oracleNativeQueryInParts = nq["oracle_native_query_inline"]

        if (oracleNativeQueryInParts == null) {
            fail("Couldn't find oracle_native_query_inline native query")
        } else {
            when (val x = oracleNativeQueryInParts.sql) {
                is NativeQuerySql.Inline ->
                    assert(x.sql == "SELECT * FROM CHINOOK.ARTIST WHERE ARTISTID = {{ARTISTID}}")
                else -> fail("oracle_native_query_inline native query is supposed to be inline")
            }
        }
    }


    @Test
    fun parseNativeQueryFromFile() {
        val json = this::class.java.getResource("/NativeQueryFromFile.json").readText(Charsets.UTF_8)
        val nq = mapper.readValue<Map<String, NativeQueryInfo>>(json)
        val employeeByIdNativeQuery = nq["EmployeeById"]

        if (employeeByIdNativeQuery == null) {
            fail("Couldn't find EmployeeById native query")
        } else {
            when (val x = employeeByIdNativeQuery.sql) {
                is NativeQuerySql.FromFile ->
                    assert(x.filePath == "native-queries/employees-by-id.sql")
                else -> fail("EmployeeById native query is supposed to be NativeQuerySql.FromFile")
            }
        }
    }

}
