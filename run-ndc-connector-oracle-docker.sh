#!/usr/bin/env bash
set -e # halt script on error
set -x # print commands as they are executed

config_file_location=ndc-connector-oracle/connector.config.json
if [ ! -f "$config_file_location" ]; then
  echo "File $config_file_location does not exist. Exiting."
  exit 1
fi

docker run \
  --rm \
  -p 8100:8100 \
  -v $PWD/$config_file_location:/etc/connector/connector.config.json \
  ndc-connector-oracle:latest