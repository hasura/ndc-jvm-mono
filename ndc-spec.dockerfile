FROM rust:1.78-buster

ARG NDC_SPEC_VERSION=v0.1.2

# Install git
RUN apt-get update && apt-get install -y git

# Clone ndc-spec repo
WORKDIR /app
RUN git clone https://github.com/hasura/ndc-spec --branch ${NDC_SPEC_VERSION}

# Build the ndc-test binary
WORKDIR /app/ndc-spec
RUN cargo build --release --bin ndc-test
RUN mkdir ./snapshots

# Set entrypoint
ENTRYPOINT ["/app/ndc-spec/target/release/ndc-test"]
CMD ["test"]
