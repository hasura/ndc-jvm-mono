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
RUN ./gradlew :ndc-cli:installDist --no-daemon --console=plain -x test

# Final stage: JRE-only runtime (no Maven/JDK tooling) on the latest UBI 9.9 minimal image.
# Built on ubi-minimal directly instead of ubi9/openjdk-21-runtime so that fixes shipped in new
# UBI image builds are included even before they reach the UBI package repos, where
# `microdnf upgrade` can't install them (e.g. krb5-libs 1.21.1-10.el9_9.2 in Nov 2026).
# The `9.9` tag always points at the newest 9.9 build.
FROM registry.access.redhat.com/ubi9/ubi-minimal:9.9

# Red Hat's Java launcher (run-java.sh) and JVM defaults from ubi9/openjdk-21-runtime, so the JVM
# starts with the same options and honours the same JAVA_* environment variables as before.
COPY --from=registry.access.redhat.com/ubi9/openjdk-21-runtime:1.24 /opt/jboss/container /opt/jboss/container

# Same JRE as ubi9/openjdk-21-runtime, all available RHEL security errata, and the same
# non-root user (185, "default") that image provides.
USER root
RUN microdnf -y install --nodocs --setopt=install_weak_deps=0 java-21-openjdk-headless \
    && microdnf -y upgrade --refresh --nodocs --setopt=install_weak_deps=0 \
    && microdnf clean all \
    && rm -rf /var/cache/yum \
    && echo 'default:x:185:0:Default user:/home/default:/sbin/nologin' >> /etc/passwd \
    && echo 'default:x:185:default' >> /etc/group \
    && mkdir -p /home/default /deployments \
    && chown 185:0 /home/default \
    && chmod 770 /home/default

ENV HOME=/home/default \
    JAVA_HOME=/usr/lib/jvm/jre \
    JAVA_VENDOR=openjdk \
    JAVA_VERSION=21 \
    JAVA_DATA_DIR=/deployments/data \
    JBOSS_CONTAINER_OPENJDK_JRE_MODULE=/opt/jboss/container/openjdk/jre \
    JBOSS_CONTAINER_JAVA_PROXY_MODULE=/opt/jboss/container/java/proxy \
    JBOSS_CONTAINER_JAVA_JVM_MODULE=/opt/jboss/container/java/jvm \
    JBOSS_CONTAINER_UTIL_LOGGING_MODULE=/opt/jboss/container/util/logging/ \
    JBOSS_CONTAINER_JAVA_RUN_MODULE=/opt/jboss/container/java/run

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
WORKDIR /app

# The "/app/output" directory is used by the NDC CLI "update" command as a bind-mount volume:
#
# docker run \
#  -e HASURA_PLUGIN_CONNECTOR_CONTEXT_PATH \
#  -v ${HASURA_PLUGIN_CONNECTOR_CONTEXT_PATH}:/app/output
RUN mkdir -p /app/output && chmod 777 /app/output

COPY --from=build /build/ndc-cli/build/install/ndc-cli /app
USER 185
ENTRYPOINT ["/app/bin/ndc-cli"]
