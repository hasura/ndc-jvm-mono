run-mysql-connector:
	export HASURA_CONFIGURATION_DIRECTORY=$(shell pwd)/ndc-connector-mysql && \
	./gradlew :ndc-connector-mysql:quarkusDev --console=plain

run-mysql-tests:
	cd ../ndc-spec && \
	cargo run --bin ndc-test replay --endpoint http://localhost:8080 --snapshots-dir ../ndc-jvm-mono/ndc-spec-tests/mysql


run-trino-connector:
	export HASURA_CONFIGURATION_DIRECTORY=$(shell pwd)/ndc-connector-trino && \
	./gradlew :ndc-connector-trino:quarkusDev --console=plain

run-trino-cli-introspection:
	HASURA_CONFIGURATION_DIRECTORY=/home/user/projects/ndc-jvm-mono/ndc-connector-trino \
	./gradlew :ndc-cli:run --args="\
update \
jdbc:trino://localhost:8090?user=trino \
--database=TRINO \
--schemas=chinook_mysql \
--fully-qualify-names=true"

run-snowflake-cli-introspection:
ifndef SNOWFLAKE_JDBC_URL
	$(error SNOWFLAKE_JDBC_URL is not set)
endif
	HASURA_CONFIGURATION_DIRECTORY=/home/user/projects/ndc-jvm-mono/ndc-connector-snowflake \
	./gradlew :ndc-cli:run --args="\
update \
$(SNOWFLAKE_JDBC_URL) \
--database=SNOWFLAKE \
--schemas=PUBLIC \
--fully-qualify-names=false"

run-oracle-connector:
	export HASURA_CONFIGURATION_DIRECTORY=$(shell pwd)/ndc-connector-oracle && \
	./gradlew :ndc-connector-oracle:quarkusDev --console=plain

build-and-push-cli:
	ifndef NDC_JVM_CLI_VERSION
		$(error NDC_JVM_CLI_VERSION is not set)
	endif
	docker buildx build \
        --platform linux/amd64,linux/arm64 \
        -t ghcr.io/hasura/ndc-jvm-cli:$(NDC_JVM_CLI_VERSION) \
        -f ndc-cli.dockerfile \
        --push .