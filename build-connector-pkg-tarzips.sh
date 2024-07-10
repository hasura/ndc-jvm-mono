#!/usr/bin/env bash
set -e -u

# This script is used to build the package tarballs for the connectors
# It is intended to be run from the root of the repository
for connector in "ndc-connector-mysql" "ndc-connector-oracle" "ndc-connector-snowflake"; do
  pushd ${connector}
  tar -czf ../${connector}-package.tar.gz ./.hasura-connector
  echo "Created ${connector}-package.tar.gz"
  popd
done