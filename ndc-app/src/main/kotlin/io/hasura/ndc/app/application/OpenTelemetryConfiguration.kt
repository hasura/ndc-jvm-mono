import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SpanProcessor
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton

@ApplicationScoped
class OpenTelemetryConfiguration {

    @Produces
    @Singleton
    fun customSpanProcessor(): SpanProcessor = DefaultAttributeSpanProcessor()

    private class DefaultAttributeSpanProcessor : SpanProcessor {
        override fun onStart(context: Context, span: ReadWriteSpan) {
            span.setAttribute("internal.visibility", "user")
        }

        override fun onEnd(span: ReadableSpan) {
            // no-op
        }

        override fun isStartRequired(): Boolean = true
        override fun isEndRequired(): Boolean = false
    }
}