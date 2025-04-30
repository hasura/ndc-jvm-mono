run-mysql-connector:
	export HASURA_CONFIGURATION_DIRECTORY=$(shell pwd)/ndc-connector-mysql && \
	./gradlew :ndc-connector-mysql:quarkusDev --console=plain

run-mysql-tests:
	cd ../ndc-spec && \
	cargo run --bin ndc-test replay --endpoint http://localhost:8080 --snapshots-dir ../ndc-jvm-mono/ndc-spec-tests/mysql

