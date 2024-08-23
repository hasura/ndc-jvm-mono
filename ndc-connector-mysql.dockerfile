# Build stage
FROM registry.access.redhat.com/ubi9/openjdk-21:1.20-2 AS build

ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en'
WORKDIR /build
COPY . /build

# Run Gradle build
USER root
RUN ./gradlew :ndc-connector-mysql:build --no-daemon --console=plain -x test

# Final stage
FROM registry.access.redhat.com/ubi9/openjdk-21:1.20-2

ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en'
ENV JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
ENV JAVA_APP_JAR="/app/quarkus-run.jar"

WORKDIR /app

COPY --from=build /build/ndc-connector-mysql/build/quarkus-app /app

EXPOSE 8080 5005
ENTRYPOINT [ "/opt/jboss/container/java/run/run-java.sh" ]