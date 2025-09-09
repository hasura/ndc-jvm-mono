e# Hasura NDC V3 JVM Repository 

This repository contains the source code for the Hasura NDC V3 Connectors deployed on the JVM.

It consists of the following components:

- `/ndc-ir`: The intermediate representation for the NDC V3 Connectors
- `/ndc-sqlgen`: Common SQL generation utilities for the NDC V3 Connectors
- `/ndc-cli`: The CLI tool used to introspect databases and generate NDC V3 metadata configuration
- `/ndc-app`: The base web service skeleton for NDC V3 Connectors
- `/ndc-connector-oracle`: The Oracle NDC V3 Connector

## Prerequisites

- Docker
- Java 21
    - The easiest way to install Java is via [SDKMAN](https://sdkman.io/)
        - `curl -s "https://get.sdkman.io" | bash`
        - `sdk install java 21`
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
      
## Running the CLI

To run the CLI, either launch it via the Gradle task, or build a distribution and run the bundled executable.

- To run it as a Gradle task:
    ```bash
    ./gradlew :ndc-cli:run --args="update jdbc:oracle:thin:@//localhost:1521/XE?user=chinook&password=Password123 --database ORACLE"
    ```
- To build a distribution and run the bundled executable:
    ```bash
    ./gradlew :ndc-cli:installDist
    ./ndc-cli/build/install/ndc-cli/bin/ndc-cli update "jdbc:oracle:thin:@//localhost:1521/XE?user=chinook&password=Password123" --database ORACLE
    ```
  
A bundled distribution `.zip` and `.tar` can be found in the `ndc-cli/build/distributions` directory.

## Docker

To build the Docker images, run the following commands:

```bash
docker compose build ndc-connector-oracle
```

To run the Docker images, run the following commands:

```bash
config_file_location=$(pwd)/ndc-connector-oracle/configuration.json
docker run \
  --rm \
  -p 8100:8100 \
  -v $config_file_location:/etc/connector/configuration.json \
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

## OpenTelemetry Configuration

The NDC JVM connectors support OpenTelemetry for distributed tracing and observability. You can configure telemetry export using standard OpenTelemetry environment variables:

### Environment Variables

| Variable | Description | Default | Example |
|----------|-------------|---------|---------|
| `OTEL_SERVICE_NAME` | Name of the service in traces | `ndc-jvm-mono` | `ndc-oracle` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP collector endpoint | `http://localhost:4317` | `http://jaeger:4317` |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | Protocol for OTLP export | `grpc` | `grpc` or `http/protobuf` |
| `OTEL_TRACES_EXPORTER` | Traces exporter type | `none` (disabled) | `otlp` to enable |

### Supported Protocols

- **gRPC** (`grpc`): Default protocol, typically uses port 4317
- **HTTP** (`http/protobuf`): HTTP protocol with protobuf encoding, typically uses port 4318

### Examples

#### Using Jaeger with gRPC
```bash
export OTEL_SERVICE_NAME="ndc-connector-oracle"
export OTEL_EXPORTER_OTLP_ENDPOINT="http://localhost:4317"
export OTEL_EXPORTER_OTLP_PROTOCOL="grpc"
export OTEL_TRACES_EXPORTER="otlp"
```

#### Using Jaeger with HTTP
```bash
export OTEL_SERVICE_NAME="ndc-connector-mysql"
export OTEL_EXPORTER_OTLP_ENDPOINT="http://localhost:4318"
export OTEL_EXPORTER_OTLP_PROTOCOL="http/protobuf"
export OTEL_TRACES_EXPORTER="otlp"
```

#### Disabling Telemetry Export (Default)
```bash
# Telemetry export is disabled by default
# To explicitly disable, set:
export OTEL_TRACES_EXPORTER="none"
```

### Docker Compose Setup

The provided `docker-compose.yaml` includes an optional Jaeger service for local development. To enable it:

1. Uncomment the `jaeger` service in `docker-compose.yaml`
2. Uncomment and configure the `OTEL_*` environment variables for the connector services
3. Set `OTEL_TRACES_EXPORTER: "otlp"` to enable trace export
4. Start the services: `docker-compose up`
5. Access Jaeger UI at http://localhost:16686

### Troubleshooting

If you see connection refused errors like:
```
Failed to export TraceRequestMarshalers. The request could not be executed. Full error message: Connection refused: localhost/127.0.0.1:4317
```

This means the connector is trying to export traces but no OTLP collector is running. Either:
- Set up an OTLP collector (like Jaeger)
- Disable telemetry export by setting `OTEL_TRACES_EXPORTER=none` (this is the default)
