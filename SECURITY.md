# Security & Privacy Policy

The **PixelCineRaw** project takes user privacy and security seriously.

---

## 🛡️ Privacy Guarantee

PixelCineRaw is designed as an air-gapped, privacy-respecting cinema camera application:
1. **Zero Network Communication:** The application does **not** request or use `android.permission.INTERNET`. It cannot communicate with remote servers, download external code, or transmit captured media over the network.
2. **Zero Telemetry & Analytics:** There are no tracking SDKs, advertising frameworks, crash reporting daemons, or background analytics embedded in the application.
3. **Local-Only Storage:** All video clips, `.wav` audio sidecars, `.cube` LUTs, and `.gcsv` Gyroflow logs are written directly to your device's local public media storage or app-scoped directories and never leave your hardware.

---

## 🔒 Supported Versions

Only the latest release and the current `main` branch receive active bug fixes and security reviews.

| Version | Supported |
| :--- | :--- |
| `v0.5.x` | ✅ Yes |
| `v0.3.x` | ❌ No |
| `v0.2.x` | ❌ No |
| `< v0.2.0` | ❌ No |

---

## 🚨 Reporting a Vulnerability

If you discover a potential security vulnerability or sensitive privacy issue:
1. **Do not disclose the issue publicly** in GitHub issues or discussions.
2. Contact the maintainer privately with:
   - A detailed description of the vulnerability.
   - Steps to reproduce or proof-of-concept code.
   - The device model and Android OS version tested on.
3. The maintainer will acknowledge receipt within 48 hours and work with you to test and deploy a fix.
4. Once resolved, you will be credited in the release notes.
