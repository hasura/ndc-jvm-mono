FROM registry.access.redhat.com/ubi9/openjdk-17:1.18-4

ARG JOOQ_PRO_EMAIL
ARG JOOQ_PRO_LICENSE

ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en'
ENV JOOQ_PRO_EMAIL=${JOOQ_PRO_EMAIL}
ENV JOOQ_PRO_LICENSE=${JOOQ_PRO_LICENSE}

WORKDIR /app
COPY . /app

# Run Gradle build
USER root
RUN ./gradlew build --no-daemon --console=plain -x test

ENV JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
ENV JAVA_APP_JAR="/app/ndc-connector-oracle/build/quarkus-app/quarkus-run.jar"

EXPOSE 8080 5005
ENTRYPOINT [ "/opt/jboss/container/java/run/run-java.sh" ]
