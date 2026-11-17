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
RUN ./gradlew :ndc-connector-oracle:build --no-daemon --console=plain -x test

# Final stage: JRE-only runtime image (no Maven/JDK tooling), with all available
# RHEL security errata applied on top of the published base image.
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:1.24

USER root
RUN microdnf -y upgrade --refresh --nodocs --setopt=install_weak_deps=0 \
    && microdnf clean all \
    && rm -rf /var/cache/yum

ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en'
ENV JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
ENV JAVA_APP_JAR="/app/quarkus-run.jar"

WORKDIR /app

COPY --from=build /build/ndc-connector-oracle/build/quarkus-app /app

USER 185

EXPOSE 8080 5005
ENTRYPOINT [ "/opt/jboss/container/java/run/run-java.sh" ]