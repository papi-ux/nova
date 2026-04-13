# Security Policy

## Supported Versions

- Latest tagged release
- Current `master`

## Reporting a Vulnerability

Please do not open a public issue for certificate or pairing flaws, discovery abuse, intent or exported-component abuse, file-sharing bugs, or unintended data exposure.

Prefer GitHub Security Advisories if they are enabled for the repository. If private reporting is not available, open a minimal public issue asking for a private follow-up channel and avoid posting exploit details or secrets.

## Privacy and Transport Notes

- Pairing material and saved host records are kept local to the device and excluded from Android backup and device-transfer flows.
- Moonlight-compatible discovery and initial unpaired pairing may require local-network HTTP before certificate pinning is established.
- Paired and Polaris-specific requests use TLS with pinned server-certificate checks where available.
- Performance logs are only shared when the user explicitly exports them.
