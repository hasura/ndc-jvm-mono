# Build stage
FROM registry.access.redhat.com/ubi9/openjdk-21:1.24 AS build

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
FROM registry.access.redhat.com/ubi9/openjdk-21:1.24

# eMudhra distrust: ubi9/openjdk-21:1.24 (ca-certificates 2026.2.92) already drops the
# other emSign roots; emSign Root CA - G1 is still a trusted anchor, so blocklist it in
# the OS store (which also regenerates the JVM cacerts at /etc/pki/java/cacerts).
# Becomes a no-op once RHEL removes G1 upstream; delete this block then.
USER root
RUN set -eu; \
  b=/etc/pki/ca-trust/source/blocklist/emsign-root-ca-g1.pem; \
  if keytool -exportcert -rfc -cacerts -storepass changeit -alias emsignrootca-g1 > "$b" 2>/dev/null; then \
    update-ca-trust extract; \
  else rm -f "$b"; fi; \
  ! keytool -list -cacerts -storepass changeit -alias emsignrootca-g1 >/dev/null 2>&1
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
