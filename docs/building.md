# Building & Environment Setup

This guide details the requirements, toolchain configuration, and compilation procedures for building HuntMemory from source.

---

## 🛠️ 1. Prerequisites & Toolchains

Ensure your development environment meets the following specifications:

### Android Toolchain
- **Android SDK**: `compileSdk = 37`, `minSdk = 29` (Android 10+), `targetSdk = 37`
- **Android NDK**: Version `29.0.14206865`
- **Java Development Kit**: JDK 21 LTS (Oracle OpenJDK or Eclipse Temurin)
- **Gradle**: 9.x (managed via `./gradlew`)

### Rust Toolchain
- **Rust Edition**: 2024 (rustc 1.90+)
- **Android Compilation Target**: `aarch64-linux-android`
- **NDK Cargo Helper**: `cargo-ndk`

---

## 🚀 2. Initial Setup

### Step 1: Clone the Repository
```bash
git clone https://github.com/Yervant7/HuntMemory.git
cd HuntMemory
```

### Step 2: Configure Rust Target
Add the ARM64 Android compilation target to your Rust toolchain:
```bash
rustup target add aarch64-linux-android
```

### Step 3: Install `cargo-ndk`
`cargo-ndk` automates toolchain linking between Rust and the Android NDK:
```bash
cargo install cargo-ndk
```

### Step 4: Configure `local.properties`
Set your Android SDK and NDK paths in `local.properties` in the project root:
```properties
sdk.dir=C:\\Users\\<username>\\AppData\\Local\\Android\\Sdk
ndk.dir=C:\\Users\\<username>\\AppData\\Local\\Android\\Sdk\\ndk\\29.0.14206865
```

---

## 🏗️ 3. Compiling the Project

### Option A: Standard Full Build (Recommended)
Gradle is pre-configured to automatically compile the Rust core (`libhmem_jni.so`) and package it into the final APK:

```bash
# Debug APK
.\gradlew assembleDebug

# Release APK (Minified & Optimized)
.\gradlew assembleRelease
```

The compiled APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

### Option B: Compiling the Rust Core Manually
To iterate quickly on the native engine without building the entire Android application:

```bash
# Navigate to the Rust workspace
cd app/src/main/hmem

# Build release shared library for ARM64
cargo ndk -t arm64-v8a build --release
```

The output library is produced at:
`app/src/main/hmem/target/aarch64-linux-android/release/libhmem_jni.so`

To copy the binary into the Android project's `jniLibs` directory:
```bash
# From project root:
.\gradlew copyRustLib
```

---

## 🔑 4. Release Signing (LSPosed apksign)

HuntMemory integrates LSPosed's [`lsplugin.apksign`](https://github.com/LSPosed/LSPlugin) Gradle plugin to streamline APK signing without hardcoding sensitive keystores or credentials into build scripts.

If signing credentials are not configured, `lsplugin.apksign` automatically falls back to standard debug signing.

### Local Signing Setup

To sign release builds locally, provide the signing properties via your user-level `~/.gradle/gradle.properties` (recommended) or project `gradle.properties`:

```properties
KEYSTORE_FILE=/path/to/your/release.keystore
KEYSTORE_PASSWORD=your_keystore_password
KEY_ALIAS=your_key_alias
KEY_PASSWORD=your_key_password
```

Or pass them directly via Gradle command-line parameters:

```bash
.\gradlew assembleRelease -PKEYSTORE_FILE="release.keystore" -PKEYSTORE_PASSWORD="password" -PKEY_ALIAS="alias" -PKEY_PASSWORD="password"
```

### GitHub Actions CI/CD Signing Setup

In GitHub Actions CI (`ci.yml` and `release.yml`), signing is configured securely via **GitHub Repository Secrets**:

1. Convert your keystore file to Base64:
   - **Linux / macOS**:
     ```bash
     base64 -w 0 release.keystore > keystore_base64.txt
     ```
   - **Windows (PowerShell)**:
     ```powershell
     [Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore")) | Out-File -Encoding ascii keystore_base64.txt
     ```
2. Navigate to your repository on GitHub: **Settings** > **Secrets and variables** > **Actions**.
3. Create the following **Repository Secrets**:
   - `KEYSTORE_BASE64`: The full content of `keystore_base64.txt`.
   - `KEYSTORE_PASSWORD`: The keystore password.
   - `KEY_ALIAS`: The key alias name.
   - `KEY_PASSWORD`: The private key password.

When these secrets are present, the CI and Release workflows automatically decode the keystore to `$RUNNER_TEMP/release.jks` and supply `ORG_GRADLE_PROJECT_KEYSTORE_*` environment variables to Gradle. If the secrets are omitted (e.g. in public forks or pull requests), CI cleanly falls back to debug signing.

---

## 📱 5. Target Device Requirements

To run HuntMemory on your device:

1. **Architecture**: Physical 64-bit ARM device (`arm64-v8a` / `aarch64`).
2. **Android Version**: Android 10.0+ (API level 29 or higher).
3. **Kernel Version**: Kernel Linux (4.14+).
4. **Root Environment**:
   - Magisk 26+, KernelSU, or APatch with root granted.
5. **KernelPatch & HuntMemory-KPM(HMKPM)**:
   - Device kernel patched with **[KernelPatch](https://github.com/bmax121/KernelPatch)**.
   - Or Device kernel patched with **[KPM-Manager](https://github.com/Yervant7/KPM-Manager)**.
   - **[HMKPM](https://github.com/Yervant7/HuntMemory-KPM)** loaded to enable direct MMU memory manipulation via the `SYS_GETRESUID` syscall hook.
6. **Overlay Permission**:
   - Grant **"Display over other apps"** (`SYSTEM_ALERT_WINDOW`) when prompted on the initial launch.

---

## 🔧 6. Troubleshooting & FAQ

### `cargo-ndk: command not found`
Ensure `cargo` binary directory (`~/.cargo/bin` or `%USERPROFILE%\.cargo\bin`) is included in your system `PATH`.

### `NDK not configured`
Verify that `ANDROID_NDK_HOME` or `ndk.dir` in `local.properties` points to the exact NDK version `29.0.14206865`.

### Missing `libhmem_jni.so` at Runtime
Run `.\gradlew copyRustLib` or `.\gradlew assembleDebug` to trigger the automated build and copy step.

---

## 📖 7. Documentation Build & Local Preview

The documentation is powered by **Material for MkDocs** and deployed automatically to GitHub Pages.

### Local Setup & Live Server
To preview documentation locally with hot-reloading:

```bash
# Install documentation dependencies
pip install -r docs/requirements.txt

# Launch local preview server (default: http://127.0.0.1:8000)
mkdocs serve

# Strict build validation (ensures no broken links or syntax errors)
mkdocs build --strict
```

---

## 🤖 8. CI/CD & Automated Workflows

HuntMemory utilizes **GitHub Actions** for continuous integration, automated documentation deployment, and release management:

### Documentation Deployment (`pages.yml`)
- **Trigger**: Pushes to `main` / `master` modifying `docs/**`, `mkdocs.yml`, or the workflow itself.
- **Workflow**: Sets up Python 3.12, installs dependencies from `docs/requirements.txt`, runs `mkdocs build --strict`, and deploys the static site artifact to **GitHub Pages** (`https://yervant7.github.io/HuntMemory/`).

### Continuous Integration (`ci.yml`)
- **Trigger**: Every push or pull request targeting `main` / `master`.
- **Validation Steps**:
   1. **Rust Core**: Verifies code formatting (`cargo fmt`), runs host test suites (`cargo test`), and enforces strict compiler/linter checks (`cargo clippy -D warnings`).
   2. **Android Build**: Configures JDK 21, Android SDK (API 37), NDK `29.0.14206865`, and `cargo-ndk`. Compiles the application and generates `arm64-v8a` Debug and Release APKs (signed when repository keystore secrets are configured).
- **Artifacts**: Debug and Release APKs are uploaded as workflow artifacts for immediate testing.

### Automated Releases (`release.yml`)
- **Trigger**: Pushing a version tag matching `v*` (e.g., `git tag v3.0.0 && git push origin v3.0.0`) or manual trigger via `workflow_dispatch`.
- **Output**: Builds the optimized Release APK (signed when repository keystore secrets are configured), generates cryptographic SHA256 checksums (`.sha256`), and publishes a GitHub Release with downloadable binaries and release notes.

### Automated Dependency Management (`dependabot.yml`)
- Weekly scheduled checks for GitHub Actions, Rust Cargo crates, and Gradle / Android dependencies.

