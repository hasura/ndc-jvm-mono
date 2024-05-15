package io.hasura.ndc.ir

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Paths

object RequestParser {

    val mapper = jacksonObjectMapper()

    fun <T> parseRequests(path: String, requestClass: Class<T>) {
        val results = mapOf("succeeded" to mutableListOf<String>(), "failed" to mutableListOf<String>())
        val dirPath = this::class.java.getResource(path).toURI()
        Files.walk(Paths.get(dirPath))
            .filter { Files.isRegularFile(it) }
            .map { dirPath.relativize(it.toUri()).path }
            .forEach {
                try {
                    parseRequest("${path}/$it", requestClass)
                    results["succeeded"]!!.add(it)
                } catch (e: Exception) {
                    results["failed"]!!.add(it)
                }
            }
        println("SUCCEEDED:")
        results["succeeded"]!!.forEach { println("\t$it") }
        println("FAILED:")
        results["failed"]!!.forEach { println("\t$it") }

        if(results["failed"]!!.isNotEmpty()) throw RuntimeException("Failed to parse all test files")
    }

    fun <T> parseRequest(path:String, requestClass: Class<T>) {
        val json = this::class.java.getResource(path)?.readText()
        mapper.readValue(json, requestClass)
    }
}
