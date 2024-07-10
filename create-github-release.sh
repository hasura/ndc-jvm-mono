#!/bin/bash

# Variables
OWNER="hasura"
REPO="ndc-jvm-mono"
VERSION="v0.1.0"

SUBDIRS=("ndc-connector-oracle" "ndc-connector-mysql" "ndc-connector-snowflake")

# Loop through each subdirectory and create a release
for SUBDIR in "${SUBDIRS[@]}"; do
  TAG="${SUBDIR#ndc-connector-}/${VERSION}"  # Create tag like oracle/v1.0.0
  RELEASE_NAME="${SUBDIR#ndc-connector-} Release ${VERSION}"
  RELEASE_DESCRIPTION="Release for ${SUBDIR#ndc-connector-} version ${VERSION}"
  FILE_PATH="${SUBDIR}/package.tar.gz"

  # Create a new release
  gh release create "$TAG" \
    --repo "$OWNER/$REPO" \
    --title "$RELEASE_NAME" \
    --notes "$RELEASE_DESCRIPTION" \
    "$FILE_PATH"

  # Verify the release
  if [ $? -eq 0 ]; then
    echo "Release ${RELEASE_NAME} created and file uploaded successfully for ${SUBDIR}."
  else
    echo "Failed to create release ${RELEASE_NAME} or upload file for ${SUBDIR}."
  fi
done