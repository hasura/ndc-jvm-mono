# Build stage
# Build on the host platform: the Quarkus fast-jar / installDist output is architecture-
# independent, so only the final stage is built per target platform (no QEMU Gradle builds).
FROM --platform=$BUILDPLATFORM registry.access.redhat.com/ubi9/openjdk-21:1.24 AS build

ARG JOOQ_PRO_EMAIL
ARG JOOQ_PRO_LICENSE

ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en'
ENV JOOQ_PRO_EMAIL=${JOOQ_PRO_EMAIL}
ENV JOOQ_PRO_LICENSE=${JOOQ_PRO_LICENSE}

WORKDIR /build
COPY . /build

# Run Gradle build
USER root
RUN ./gradlew :ndc-connector-oracle:build --no-daemon --console=plain -x test

# Final stage: JRE-only runtime image (no Maven/JDK tooling), with all available
# RHEL security errata applied on top of the published base image.
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:1.24

USER root
RUN microdnf -y upgrade --refresh --nodocs --setopt=install_weak_deps=0 \
    && microdnf clean all \
    && rm -rf /var/cache/yum

# eMudhra distrust (from #102): if emSign Root CA - G1 is still a trusted anchor in the
# OS store, blocklist it (update-ca-trust also regenerates the JVM cacerts) and fail the
# build if it is still present. Becomes a no-op once RHEL removes G1 upstream.
RUN set -eu; \
  b=/etc/pki/ca-trust/source/blocklist/emsign-root-ca-g1.pem; \
  if keytool -exportcert -rfc -cacerts -storepass changeit -alias emsignrootca-g1 > "$b" 2>/dev/null; then \
    update-ca-trust extract; \
  else rm -f "$b"; fi; \
  ! keytool -list -cacerts -storepass changeit -alias emsignrootca-g1 >/dev/null 2>&1

# Java4Shell (CVE-2026-114938) TEMPORARY MITIGATION -- not a fix. Red Hat had not shipped a
# fixed java-21-openjdk for UBI 9 as of 2026-11-18 (RHSB-2026-018), so this image still
# carries the affected 21.0.13.0.9 JVM. Disabling the Base64 intrinsics is Red Hat's
# documented workaround. JDK_JAVA_OPTIONS is read by the `java` launcher, so it applies to
# both run-java.sh and the CLI start script. Remove once a fixed JDK RPM is in the base.
ENV JDK_JAVA_OPTIONS="-XX:-UseBASE64Intrinsics"

ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en'
ENV JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
ENV JAVA_APP_JAR="/app/quarkus-run.jar"

WORKDIR /app

COPY --from=build /build/ndc-connector-oracle/build/quarkus-app /app

USER 185

EXPOSE 8080 5005
ENTRYPOINT [ "/opt/jboss/container/java/run/run-java.sh" ]