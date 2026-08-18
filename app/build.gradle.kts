import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("21")
    }
}

android {
    namespace = "com.yervant.huntmem"
    compileSdk = 37

    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.yervant.huntmem"
        minSdk = 30
        targetSdk = 37
        versionCode = 300
        versionName = "3.0.0"

        ndk {
            abiFilters.add("arm64-v8a")
        }
        signingConfig = signingConfigs.getByName("debug")
        multiDexEnabled = false
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
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
    sourceSets {
        getByName("main") {
            jniLibs.directories.add("src/main/jniLibs")
        }
    }
    buildToolsVersion = "37.0.0"
    compileSdkMinor = 1
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
 *   cd app/src/main/hmem && cargo ndk -t arm64-v8a build --release
 */
val rustDir = layout.projectDirectory.dir("src/main/hmem")
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")
val rustOutputSo = rustDir.file("target/aarch64-linux-android/release/libhmem_jni.so")
val jniOutputSo = jniLibsDir.file("libhmem_jni.so")

val buildRustDebug = tasks.register<Exec>("buildRustDebug") {
    description = "Compiles libhmem_jni.so in debug mode for arm64-v8a"
    group = "rust"
    workingDir = rustDir.asFile
    inputs.dir(rustDir.dir("hmem_jni/src"))
    inputs.file(rustDir.file("Cargo.toml"))
    inputs.file(rustDir.file("hmem_jni/Cargo.toml"))
    commandLine("cargo", "ndk", "-t", "arm64-v8a", "build")
    outputs.file(rustDir.file("target/aarch64-linux-android/debug/libhmem_jni.so"))
}

val buildRustRelease = tasks.register<Exec>("buildRustRelease") {
    description = "Compiles libhmem_jni.so in release mode for arm64-v8a"
    group = "rust"
    workingDir = rustDir.asFile
    inputs.dir(rustDir.dir("hmem_jni/src"))
    inputs.file(rustDir.file("Cargo.toml"))
    inputs.file(rustDir.file("hmem_jni/Cargo.toml"))
    commandLine("cargo", "ndk", "-t", "arm64-v8a", "build", "--release")
    outputs.file(rustOutputSo)
}

val copyRustLib = tasks.register<Copy>("copyRustLib") {
    description = "Copies compiled libhmem_jni.so to jniLibs/arm64-v8a/"
    group = "rust"
    dependsOn(buildRustRelease)
    from(rustOutputSo)
    into(jniLibsDir)
}

val cleanRust = tasks.register<Delete>("cleanRust") {
    description = "Cleans libhmem_jni.so in jniLibs and Rust build artifacts"
    group = "rust"
    delete(jniOutputSo)
    delete(rustDir.dir("target"))
}

tasks.named("clean") {
    dependsOn(cleanRust)
}

tasks.named("preBuild") {
    dependsOn(copyRustLib)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.annotation)
    implementation(libs.coil.compose)
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
}