# Contributing to PixelCineRaw

Thank you for your interest in contributing to **PixelCineRaw**! We welcome bug reports, performance optimizations, feature improvements, and color science refinements from Android developers and filmmakers.

Please take a moment to review these contribution guidelines before submitting pull requests or opening issues.

---

## 📜 Code of Conduct & Licensing

By contributing to this repository, you agree that your contributions will be licensed under the [GNU General Public License v3.0 (GPLv3)](LICENSE).

- **Copyleft Respect:** All new code added to PixelCineRaw must be compatible with the GPLv3. Proprietary or closed-source code is strictly prohibited.
- **Attribution:** Original author copyright notices must remain intact. If you write significant new modules, you may add your copyright notice alongside existing notices.

---

## 🛠️ Development Setup & Workflow

### 1. Fork & Branch
1. Fork the repository on GitHub.
2. Clone your fork locally:
   ```bash
   git clone https://github.com/<your-username>/PixelCineRaw.git
   cd PixelCineRaw
   ```
3. Create a dedicated topic branch:
   ```bash
   git checkout -b feature/my-feature-name
   # or
   git checkout -b fix/my-bugfix
   ```

### 2. Testing on Physical Camera2 Hardware
Camera2 low-level APIs (such as `10-bit HEVC`, `ColorSpaceProfiles`, `OIS/EIS`, `ManualSensor`, and custom `StreamConfigurationMap`) **cannot be simulated reliably on Android Virtual Devices (emulators)**.

Before submitting a pull request, you **must test your changes on a physical Android device** supporting:
- Android 13 or newer (target device: Google Pixel 9/8/7 series or flagship Snapdragon devices)
- Camera2 `HARDWARE_LEVEL_FULL` or `HARDWARE_LEVEL_3`

Verify that:
- Viewfinder preview renders at 1:1 true square aspect ratio without stretching or squeezing.
- Video recording starts, encodes, and stops cleanly without dropped frame bursts.
- Generated MP4 files contain valid color metadata (e.g. verified with `ffprobe` or MediaInfo for HLG10/HDR10+).

### 3. Code Style & Commit Conventions
- **Kotlin Idioms:** Follow standard Kotlin coding conventions. Keep composables modular, decoupled, and free of heavy recomposition loops.
- **Conventional Commits:** Write clear commit messages adhering to the Conventional Commits specification:
  - `feat(...)`: A new feature (e.g., `feat(ui): add anamorphic de-squeeze selector`)
  - `fix(...)`: A bug fix (e.g., `fix(gl): resolve aspect matrix calculation error`)
  - `docs(...)`: Documentation updates
  - `perf(...)`: Performance optimizations (e.g., `perf(camera): lock-free atomic zoom update`)
  - `refactor(...)`: Code restructuring without functional changes

---

## 🚀 Submitting a Pull Request

1. Rebase your topic branch on the latest `main`:
   ```bash
   git fetch origin
   git rebase origin/main
   ```
2. Build the project to verify that compilation passes:
   ```bash
   ./gradlew assembleDebug
   ```
3. Push to your fork and open a Pull Request against the `main` branch.
4. Fill out the Pull Request template detailing:
   - What the PR changes.
   - The physical device(s) and Android OS version(s) tested on.
   - Sample logs or video clips demonstrating the fix/feature where applicable.

---

## 🐛 Reporting Issues

- Use the provided [Bug Report Template](.github/ISSUE_TEMPLATE/bug_report.md).
- When reporting camera issues, **always specify**:
  1. Device model (e.g., Pixel 9a, Pixel 8 Pro).
  2. Android version & build number.
  3. Format & Codec settings used (`4K 24p ALL-I 280M`, `HLG10`, etc.).
  4. Relevant `adb logcat` output tagged with `PixelCineRaw`, `HlgGlRenderer`, or `Camera2Controller`.
