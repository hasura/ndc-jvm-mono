# Build stage
FROM registry.access.redhat.com/ubi9/openjdk-21:1.20-2 AS build

ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en'
WORKDIR /build
COPY . /build

# Run Gradle build
USER root
RUN ./gradlew :ndc-cli:installDist --no-daemon --console=plain -x test

# Final stage
FROM registry.access.redhat.com/ubi9/openjdk-21:1.20-2

ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en'
WORKDIR /app
USER root

# The "/app/output" directory is used by the NDC CLI "update" command as a bind-mount volume:
#
# docker run \
#  -e HASURA_PLUGIN_CONNECTOR_CONTEXT_PATH \
#  -v ${HASURA_PLUGIN_CONNECTOR_CONTEXT_PATH}:/app/output
RUN mkdir -p /app/output && chmod 777 /app/output

COPY --from=build /build/ndc-cli/build/install/ndc-cli /app
ENTRYPOINT ["/app/bin/ndc-cli"]
