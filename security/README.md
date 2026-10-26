# eMudhra certificate distrust

`block-emudhra.sh JAVA_HOME` appends the SHA-256 fingerprints of the 16 disclosed intermediate certificate variants (12 TLS and 4 code-signing, eight distinct public keys) to OpenJDK's `lib/security/blocked.certs`. Existing entries are preserved. The script requires the existing SHA-256 fingerprint algorithm and fails the image build otherwise.

Source: https://emudhra.we-have-a-problem.com/impacted-intermediates.json (retrieved 21 October 2026).
Mechanism: OpenJDK `sun.security.util.UntrustedCertificates` / PKIX certificate validation.

Every connector and CLI image in this repository runs it against the final-stage `ubi9/openjdk-21` runtime (`$JAVA_HOME`), as root, then switches back to the image's default user (185).

This is certificate-fingerprint distrust of the disclosed variants, not a general SPKI blocklist. It does not rely on live OCSP/CRL availability or on removing a root from `cacerts`. New certificate variants would require updating the list. Custom certificate validators that bypass the JDK PKIX checks, and native (non-JVM) clients that use the OS trust store, are not covered.

## Validation

Before release, test all 12 direct/cross-signed chains using https://emudhra.we-have-a-problem.com/chains.zip and an unrelated valid control against the built image. Require certificate-validation errors, not network failures. Build and publish new immutable image versions (`<db>/v<version>` tags); updating source does not update existing images or deployments.
