import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.lsplugin.apksign)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("21")
        allWarningsAsErrors = true
        extraWarnings = true
    }
}

apksign {
    storeFileProperty = "KEYSTORE_FILE"
    storePasswordProperty = "KEYSTORE_PASSWORD"
    keyAliasProperty = "KEY_ALIAS"
    keyPasswordProperty = "KEY_PASSWORD"
}

fun getCargoPackageVersion(packageName: String): String {
    val lockFile = layout.projectDirectory.file("src/main/hmem/Cargo.lock").asFile
    if (lockFile.exists()) {
        val lockText = lockFile.readText()
        val regex = Regex("""\[\[package\]\]\r?\nname\s*=\s*"$packageName"\r?\nversion\s*=\s*"([^"]+)"""")
        regex.find(lockText)?.groupValues?.get(1)?.let { return it }
    }
    val tomlFile = layout.projectDirectory.file("src/main/hmem/hmem_jni/Cargo.toml").asFile
    if (tomlFile.exists()) {
        val tomlText = tomlFile.readText()
        if (packageName == "hmem_jni") {
            val pkgVerRegex = Regex("""\[package\][\s\S]*?version\s*=\s*"([^"]+)"""")
            pkgVerRegex.find(tomlText)?.groupValues?.get(1)?.let { return it }
        }
        val depRegex = Regex("""$packageName\s*=\s*(?:\{[^}]*version\s*=\s*"([^"]+)"|"([^"]+)")""")
        depRegex.find(tomlText)?.let {
            val match = it.groupValues[1].ifEmpty { it.groupValues[2] }
            if (match.isNotEmpty()) return match
        }
    }
    return "unknown"
}

android {
    namespace = "com.yervant.huntmem"
    compileSdk = 37

    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.yervant.huntmem"
        minSdk = 29
        targetSdk = 37
        versionCode = 310
        versionName = "3.1.0"

        ndk {
            abiFilters.add("arm64-v8a")
        }
        signingConfig = signingConfigs.getByName("debug")
        multiDexEnabled = false

        // Automatic version extraction for Dependency Credits
        buildConfigField("String", "DEP_VERSION_COMPOSE_BOM", "\"${libs.versions.composeBom.get()}\"")
        buildConfigField("String", "DEP_VERSION_MATERIAL3", "\"${libs.versions.material3.get()}\"")
        buildConfigField("String", "DEP_VERSION_LIBSU", "\"${libs.versions.libsu.get()}\"")
        buildConfigField("String", "DEP_VERSION_COROUTINES", "\"${libs.versions.kotlinxCoroutinesAndroid.get()}\"")
        buildConfigField("String", "DEP_VERSION_COIL", "\"${libs.versions.coilCompose.get()}\"")
        buildConfigField("String", "DEP_VERSION_ACTIVITY_COMPOSE", "\"${libs.versions.activityCompose.get()}\"")
        buildConfigField("String", "DEP_VERSION_LIFECYCLE", "\"${libs.versions.lifecycle.get()}\"")
        buildConfigField("String", "DEP_VERSION_CORE_KTX", "\"${libs.versions.coreKtx.get()}\"")
        buildConfigField("String", "DEP_VERSION_APPCOMPAT", "\"${libs.versions.appcompat.get()}\"")
        buildConfigField("String", "DEP_VERSION_MATERIAL_ICONS", "\"${libs.versions.materialIconsExtended.get()}\"")
        buildConfigField("String", "DEP_VERSION_ANNOTATION", "\"${libs.versions.annotation.get()}\"")
        buildConfigField("String", "DEP_VERSION_KOTLIN", "\"${libs.versions.kotlin.get()}\"")
        buildConfigField("String", "DEP_VERSION_SERIALIZATION", "\"${libs.versions.kotlinxSerialization.get()}\"")
        buildConfigField("String", "DEP_VERSION_AGP", "\"${libs.versions.agp.get()}\"")

        buildConfigField("String", "RUST_VERSION_HMEM_JNI", "\"${getCargoPackageVersion("hmem_jni")}\"")
        buildConfigField("String", "RUST_VERSION_JNI", "\"${getCargoPackageVersion("jni")}\"")
        buildConfigField("String", "RUST_VERSION_LIBC", "\"${getCargoPackageVersion("libc")}\"")
        buildConfigField("String", "RUST_VERSION_MLUA", "\"${getCargoPackageVersion("mlua")}\"")
        buildConfigField("String", "RUST_VERSION_SERDE", "\"${getCargoPackageVersion("serde")}\"")
        buildConfigField("String", "RUST_VERSION_SERDE_JSON", "\"${getCargoPackageVersion("serde_json")}\"")
        buildConfigField("String", "RUST_VERSION_MEMCHR", "\"${getCargoPackageVersion("memchr")}\"")
        buildConfigField("String", "RUST_VERSION_COMBINE", "\"${getCargoPackageVersion("combine")}\"")
        buildConfigField("String", "RUST_VERSION_SIMD_CESU8", "\"${getCargoPackageVersion("simd_cesu8")}\"")
        buildConfigField("String", "RUST_VERSION_THISERROR", "\"${getCargoPackageVersion("thiserror")}\"")
        buildConfigField("String", "RUST_VERSION_WALKDIR", "\"${getCargoPackageVersion("walkdir")}\"")
        buildConfigField("String", "RUST_VERSION_ITOA", "\"${getCargoPackageVersion("itoa")}\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            multiDexEnabled = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
    buildToolsVersion = "37.0.0"
    compileSdkMinor = 1

    //noinspection WrongGradleMethod
    base {
        archivesName.set("HuntMemory")
    }
}

/**
 * Builds the Rust hmem_jni library for arm64-v8a using cargo-ndk.
 *
 * Requirements:
 *   - rustup target: aarch64-linux-android
 *   - cargo-ndk installed: cargo install cargo-ndk
 *   - Android NDK configured
 *
 * Manual compilation:
 *   cd app/src/main/hmem && cargo ndk -t arm64-v8a build [--release]
 */
val rustDir = layout.projectDirectory.dir("src/main/hmem")
val jniDebugArm64Dir = layout.projectDirectory.dir("src/debug/jniLibs/arm64-v8a")
val jniReleaseArm64Dir = layout.projectDirectory.dir("src/release/jniLibs/arm64-v8a")
val rustDebugSo = rustDir.file("target/aarch64-linux-android/debug/libhmem_jni.so")
val rustReleaseSo = rustDir.file("target/aarch64-linux-android/release/libhmem_jni.so")

val buildRustDebug = tasks.register<Exec>("buildRustDebug") {
    description = "Compiles libhmem_jni.so in debug mode for arm64-v8a (fast compilation, debug symbols, debug assertions)"
    group = "rust"
    workingDir = rustDir.asFile
    inputs.dir(rustDir.dir("hmem_jni/src"))
    inputs.file(rustDir.file("Cargo.toml"))
    inputs.file(rustDir.file("hmem_jni/Cargo.toml"))
    commandLine("cargo", "ndk", "-t", "arm64-v8a", "build")
    outputs.file(rustDebugSo)
}

val buildRustRelease = tasks.register<Exec>("buildRustRelease") {
    description = "Compiles libhmem_jni.so in release mode for arm64-v8a (LTO, opt-level=3, stripped debug info)"
    group = "rust"
    workingDir = rustDir.asFile
    inputs.dir(rustDir.dir("hmem_jni/src"))
    inputs.file(rustDir.file("Cargo.toml"))
    inputs.file(rustDir.file("hmem_jni/Cargo.toml"))
    commandLine("cargo", "ndk", "-t", "arm64-v8a", "build", "--release")
    outputs.file(rustReleaseSo)
}

val copyRustDebug = tasks.register<Copy>("copyRustDebug") {
    description = "Copies debug libhmem_jni.so to src/debug/jniLibs/arm64-v8a/"
    group = "rust"
    dependsOn(buildRustDebug)
    from(rustDebugSo)
    into(jniDebugArm64Dir)
}

val copyRustRelease = tasks.register<Copy>("copyRustRelease") {
    description = "Copies release libhmem_jni.so to src/release/jniLibs/arm64-v8a/"
    group = "rust"
    dependsOn(buildRustRelease)
    from(rustReleaseSo)
    into(jniReleaseArm64Dir)
}

val cleanRust = tasks.register<Delete>("cleanRust") {
    description = "Cleans libhmem_jni.so in jniLibs and Rust build artifacts"
    group = "rust"
    delete(layout.projectDirectory.dir("src/main/jniLibs"))
    delete(layout.projectDirectory.dir("src/debug/jniLibs"))
    delete(layout.projectDirectory.dir("src/release/jniLibs"))
    delete(rustDir.dir("target"))
}

tasks.named("clean") {
    dependsOn(cleanRust)
}

tasks.matching { it.name == "preDebugBuild" || it.name == "mergeDebugJniLibFolders" }.configureEach {
    dependsOn(copyRustDebug)
}

tasks.matching { it.name == "preReleaseBuild" || it.name == "mergeReleaseJniLibFolders" }.configureEach {
    dependsOn(copyRustRelease)
}



dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.annotation)
    implementation(libs.coil.compose)
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
}