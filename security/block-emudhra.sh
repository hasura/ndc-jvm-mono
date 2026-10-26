#!/bin/sh
# Preserve existing JDK distrust rules and append the disclosed eMudhra variants.
set -eu
java_home=${1:?usage: block-emudhra.sh JAVA_HOME}
directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
blocked="$java_home/lib/security/blocked.certs"
test -f "$blocked"
# Fail closed if a future JDK uses a different fingerprint algorithm.
grep -Eq '^Algorithm[[:space:]]*=[[:space:]]*SHA-256[[:space:]]*$' "$blocked"
# Materialize symlinks so a jlink runtime doesn't rely on a builder-only target.
cat "$blocked" > "$blocked.tmp"
printf '\n' >> "$blocked.tmp"
cat "$directory/emudhra-blocked.properties" >> "$blocked.tmp"
mv "$blocked.tmp" "$blocked"
# All sixteen disclosed certificates must be present, including the cross-signs.
while IFS= read -r line; do
    case "$line" in ''|'#'*) continue ;; esac
    grep -Fxq "$line" "$blocked"
done < "$directory/emudhra-blocked.properties"
