# Build stage
FROM registry.access.redhat.com/ubi9/openjdk-21:1.23 AS build

ARG JOOQ_PRO_EMAIL
ARG JOOQ_PRO_LICENSE


ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en'
ENV JOOQ_PRO_EMAIL=${JOOQ_PRO_EMAIL}
ENV JOOQ_PRO_LICENSE=${JOOQ_PRO_LICENSE}

WORKDIR /build
COPY . /build

# Run Gradle build
USER root
RUN ./gradlew :ndc-cli:installDist --no-daemon --console=plain -x test

# Final stage
FROM registry.access.redhat.com/ubi9/openjdk-21:1.23

# Enforce JVM distrust of disclosed eMudhra intermediates independently of the OS CA bundle.
USER root
COPY security/ /tmp/tls-security/
RUN sh /tmp/tls-security/block-emudhra.sh "$JAVA_HOME" && rm -rf /tmp/tls-security
USER 185

ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en'
WORKDIR /app

# The "/app/output" directory is used by the NDC CLI "update" command as a bind-mount volume:
#
# docker run \
#  -e HASURA_PLUGIN_CONNECTOR_CONTEXT_PATH \
#  -v ${HASURA_PLUGIN_CONNECTOR_CONTEXT_PATH}:/app/output
RUN mkdir -p /app/output && chmod 777 /app/output

COPY --from=build /build/ndc-cli/build/install/ndc-cli /app
ENTRYPOINT ["/app/bin/ndc-cli"]
