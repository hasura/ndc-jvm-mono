package io.hasura.ndc.app.application

import io.vertx.core.http.HttpServerRequest
import jakarta.inject.Inject
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.server.ServerRequestFilter

class Filters {

    @Inject
    private lateinit var logger: Logger

    @ServerRequestFilter(priority = 0)
    fun logBodyFilter(info: UriInfo, request: HttpServerRequest, ctx: ContainerRequestContext) {
        request.body { b ->
            logger.debug("INCOMING IR:")
            logger.debug(b.result())
        }
    }

    @ServerRequestFilter
    fun tokenFilter(ctx: ContainerRequestContext): Response? {
        if(ctx.uriInfo.path.startsWith("/health")) return null

        val secret = System.getenv("HASURA_SERVICE_TOKEN_SECRET")
        if (secret.isNullOrEmpty()) {
            logger.warn("Environment variable HASURA_SERVICE_TOKEN_SECRET not set. Token validation is bypassed.")
            return null
        }

        val authHeader = ctx.getHeaderString(HttpHeaders.AUTHORIZATION)
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            logger.error("Authorization header missing or not in Bearer format")
            return Response.status(Response.Status.UNAUTHORIZED).build()
        }

        val token = authHeader.substringAfter("Bearer ")
        if (token.isEmpty()) {
            logger.error("Token is empty")
            return Response.status(Response.Status.UNAUTHORIZED).build()
        }
        if (token != secret) {
            logger.error("Token is invalid")
            return Response.status(Response.Status.UNAUTHORIZED).build()
        }

        return null
    }
}
