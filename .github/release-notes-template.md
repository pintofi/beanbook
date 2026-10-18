{{HIGHLIGHTS}}

### Download

| File | For |
|---|---|
| `beanbook-{{VERSION}}-arm64-v8a.apk` | Almost every phone made in the last decade. The smallest download. |
| `beanbook-{{VERSION}}-x86_64.apk` | Emulators and x86 Chromebooks. |
| `beanbook-{{VERSION}}-universal.apk` | Any device. Larger, because it carries both. |

Expert edition, Android 10 or later, signed with the project key. It installs over the previous release.

### Verify

Signing certificate SHA-256: `{{CERT_SHA256}}`

```
{{CHECKSUMS}}
```

Check a download with `sha256sum -c checksums.txt`, or `apksigner verify --print-certs <file>` for the certificate.

**Full changelog:** [CHANGELOG.md](https://github.com/pintofi/beanbook/blob/main/CHANGELOG.md) · [v{{PREVIOUS}}...v{{VERSION}}](https://github.com/pintofi/beanbook/compare/v{{PREVIOUS}}...v{{VERSION}})
