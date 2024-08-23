#!/bin/bash
set -euo pipefail
set -x

# Variables
OWNER="hasura"
REPO="ndc-jvm-mono"
VERSION="v1.0.1"

CONNECTORS=("ndc-connector-oracle" "ndc-connector-mysql" "ndc-connector-snowflake")

# Loop through each connector and create a release
for CONNECTOR in "${CONNECTORS[@]}"; do

  # First, build and push the Docker images
  export IMAGE_TAG="${VERSION}"
  docker compose build "${CONNECTOR}"
  docker compose push "${CONNECTOR}"

  # Create the .tar.gz with the connector's ".hasura-connector" directory
  tar -czvf "${CONNECTOR}/package.tar.gz" -C "${CONNECTOR}" .hasura-connector

  # Now, create a GitHub release
  TAG="${CONNECTOR#ndc-connector-}/${VERSION}"  # Create tag like oracle/v1.0.0
  RELEASE_NAME="${CONNECTOR#ndc-connector-} Release ${VERSION}"
  RELEASE_DESCRIPTION="Release for ${CONNECTOR#ndc-connector-} version ${VERSION}"
  FILE_PATH="${CONNECTOR}/package.tar.gz"

  # Create a new release
  gh release create "$TAG" \
    --repo "$OWNER/$REPO" \
    --title "$RELEASE_NAME" \
    --notes "$RELEASE_DESCRIPTION" \
    "$FILE_PATH"

  # Verify the release
  if [ $? -eq 0 ]; then
    echo "Release ${RELEASE_NAME} created and file uploaded successfully for ${CONNECTOR}."
  else
    echo "Failed to create release ${RELEASE_NAME} or upload file for ${CONNECTOR}."
  fi
done