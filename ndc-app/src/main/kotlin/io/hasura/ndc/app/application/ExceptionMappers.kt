package io.hasura.ndc.app.application

import com.fasterxml.jackson.databind.exc.MismatchedInputException
import jakarta.inject.Inject
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import io.hasura.ndc.ir.ErrorResponse
import org.jboss.logging.Logger

@Provider
class GenericExceptionMapper : ExceptionHandler(), jakarta.ws.rs.ext.ExceptionMapper<Throwable> {
    override fun toResponse(exception: Throwable) =
        handleExceptions(exception, Response.Status.INTERNAL_SERVER_ERROR)
}

@Provider
class JsonProcessingExceptionMapper :
    ExceptionHandler(),
    jakarta.ws.rs.ext.ExceptionMapper<MismatchedInputException> {
    override fun toResponse(exception: MismatchedInputException) =
        // this should be returning a BAD_REQUEST error, but HSpec, for now, expects a 500
        handleExceptions(exception, Response.Status.INTERNAL_SERVER_ERROR, "JSON deserialization Error")
}

@Provider
class BadRequestExceptionMapper :
    ExceptionHandler(),
    jakarta.ws.rs.ext.ExceptionMapper<BadRequestException> {
    override fun toResponse(exception: BadRequestException) =
        // this should be returning a BAD_REQUEST error, but HSpec, for now, expects a 500
        handleExceptions(exception, Response.Status.BAD_REQUEST)
}

@Provider
class DataAccessExceptionMapper :
    ExceptionHandler(),
    jakarta.ws.rs.ext.ExceptionMapper<org.jooq.exception.DataAccessException> {

    override fun toResponse(exception: org.jooq.exception.DataAccessException): Response {
        // Check if the wrapped exception is a MutationPermissionCheckFailureExceptionMapper
        val cause = exception.cause
        return handleExceptions(exception, Response.Status.INTERNAL_SERVER_ERROR)
    }
}

abstract class ExceptionHandler {

    @Inject
    private lateinit var logger: Logger

    fun handleExceptions(
        exception: Throwable,
        status: Response.Status,
        message: String? = null
    ): Response {
        try {
            return Response
                .status(status)
                .entity(
                    ErrorResponse(
                        message = message ?: exception.message ?: "An uncaught error occurred",
                        details = mapOf("stacktrace" to exception.stackTraceToString())
                    )
                )
                .build()
        } finally {
            logger.error("Uncaught exception", exception)
        }
    }
}
