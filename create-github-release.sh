#!/bin/bash

# Check if the correct number of arguments is provided
if [ "$#" -ne 2 ]; then
  echo "Usage: $0 <subdir> <version>"
  exit 1
fi

# Variables
OWNER="hasura"
REPO="ndc-jvm-mono"
SUBDIR="$1"   # Subdirectory passed as the second argument
VERSION="$2"  # Version passed as the first argument

# Create tag, release name, and description
TAG="${SUBDIR#ndc-connector-}/${VERSION}"  # Create tag like oracle/v1.0.0
RELEASE_NAME="${SUBDIR#ndc-connector-} Release ${VERSION}"
RELEASE_DESCRIPTION="Release for ${SUBDIR#ndc-connector-} version ${VERSION}"
FILE_PATH="${SUBDIR}/package.tar.gz"

# Build connector package tarball
pushd "$SUBDIR"
tar -czf package.tar.gz ./.hasura-connector
echo "Created ${SUBDIR}-package.tar.gz"
popd

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