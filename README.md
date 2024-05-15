# Hasura NDC V3 JVM Repository

This repository contains the source code for the Hasura NDC V3 Connectors deployed on the JVM.

It consists of the following components:

- `/ndc-ir`: The intermediate representation for the NDC V3 Connectors
- `/ndc-sqlgen`: Common SQL generation utilities for the NDC V3 Connectors
- `/ndc-cli`: The CLI tool used to introspect databases and generate NDC V3 metadata configuration
- `/ndc-app`: The base web service skeleton for NDC V3 Connectors
- `/ndc-connector-oracle`: The Oracle NDC V3 Connector

## Prerequisites

- Docker
- Java 17
    - The easiest way to install Java is via [SDKMAN](https://sdkman.io/)
        - `curl -s "https://get.sdkman.io" | bash`
        - `sdk install java 17`
- A `.env` file containing jOOQ Enterprise license keys
   - ```env
     JOOQ_PRO_EMAIL=
     JOOQ_PRO_LICENSE=
     ```

## Building the Connectors

To build the connectors, run the following command:

```bash
./gradlew build -x test
```

This will run the build and skip tests for each of the services.

When finished, the following files should be present:

- `/ndc-connector-oracle/build/quarkus-app/quarkus-run.jar` - The executable service JAR

## Running the Connectors

> **NOTE:**
> 
> Before a connector can be run, an environment variable `HASURA_CONFIGURATION_DIRECTORY` must be present and point to the directory containing the `connector.config.json` for that connector.
>
> For example, to use the provided Oracle test configuration file, you can use: `export HASURA_CONFIGURATION_DIRECTORY=$(pwd)/ndc-connector-oracle`.

There are two ways to run the connectors:

1. In Development Mode
    - To run the connector in development mode, run the following command:
        ```bash
        ./gradlew :ndc-connector-oracle:quarkusDev --console=plain
        ```
    - This will start the connectors in hot-reload mode, which will automatically reload when changes are made to the
      source code.
2. In Production Mode
    - To run the connector in production mode, run the following command:
        ```bash
        java -jar ndc-connector-oracle/build/quarkus-app/quarkus-run.jar
        ```

## Docker

To build the Docker images, run the following commands:

```bash
docker compose build ndc-connector-oracle
```

To run the Docker images, run the following commands:

```bash
config_file_location=$(pwd)/ndc-connector-oracle/connector.config.json
docker run \
  --rm \
  -p 8100:8100 \
  -v $config_file_location:/etc/connector/connector.config.json \
  ndc-connector-oracle:latest
```

## Testing

To run the tests against the Oracle connector, use the `ndc-spec` test binary.

This comes from the `ndc-spec` repo, which can be found here:

- https://github.com/hasura/ndc-spec

```bash
git clone https://github.com/hasura/ndc-spec
cd ndc-spec
cargo run --bin ndc-test -- test \
  --endpoint http://localhost:8100 \
  --snapshots-dir snapshots
```