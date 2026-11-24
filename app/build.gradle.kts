import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

android {
    namespace = "com.yervant.huntmem"
    compileSdk = 37

    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.yervant.huntmem"
        minSdk = 29
        targetSdk = 37
        versionCode = 400
        versionName = "4.0.0"

        ndk {
            abiFilters.add("arm64-v8a")
        }
        signingConfig = signingConfigs.getByName("debug")

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
        buildConfigField("String", "HMKPM_VERSION", "\"${libs.versions.hmkpm.get()}\"")
        buildConfigField("String", "HMKPM_ASSET_NAME", "\"hmkpm.kpm\"")
        buildConfigField("String", "HMKPM_EXPORT_FILENAME", "\"hmkpm-${libs.versions.hmkpm.get()}.kpm\"")
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

val fmtRust = tasks.register<Exec>("fmtRust") {
    description = "Formats Rust code using cargo fmt"
    group = "rust"
    workingDir = rustDir.asFile
    commandLine("cargo", "fmt", "--all")
}

val buildRustDebug = tasks.register<Exec>("buildRustDebug") {
    description = "Compiles libhmem_jni.so in debug mode for arm64-v8a (fast compilation, debug symbols, debug assertions)"
    group = "rust"
    dependsOn(fmtRust)
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
    dependsOn(fmtRust)
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

abstract class DownloadHmkpmTask : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val downloadUrl: Property<String>

    @get:Input
    abstract val assetFileName: Property<String>

    @get:Input
    @get:Optional
    abstract val localFilePath: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun execute() {
        val outDir = outputDir.get().asFile
        outDir.mkdirs()
        val targetFile = File(outDir, assetFileName.get())

        val localOverride = localFilePath.orNull?.trim().orEmpty()
        if (localOverride.isNotEmpty()) {
            val sourceFile = File(localOverride)
            if (!sourceFile.isFile || sourceFile.length() == 0L) {
                throw org.gradle.api.GradleException("Local HMKPM file not found or empty: $localOverride")
            }
            Files.copy(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return
        }

        val tmpFile = File(temporaryDir, "${assetFileName.get()}.tmp")
        var currentUrl = downloadUrl.get()
        var redirects = 0
        while (true) {
            val conn = (URI.create(currentUrl).toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "HuntMemory-Gradle-Build")
            }
            try {
                val status = conn.responseCode
                if (status in listOf(
                        HttpURLConnection.HTTP_MOVED_PERM,
                        HttpURLConnection.HTTP_MOVED_TEMP,
                        HttpURLConnection.HTTP_SEE_OTHER,
                        307,
                        308
                    )
                ) {
                    val nextLocation = conn.getHeaderField("Location")
                        ?: throw org.gradle.api.GradleException("Missing redirect Location header for $currentUrl")
                    redirects += 1
                    if (redirects > 5) {
                        throw org.gradle.api.GradleException("Too many redirects downloading HMKPM from ${downloadUrl.get()}")
                    }
                    currentUrl = URI.create(currentUrl).resolve(nextLocation).toString()
                    continue
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw org.gradle.api.GradleException(
                        "Failed to download HMKPM v${version.get()} from $currentUrl (HTTP $status)"
                    )
                }
                conn.inputStream.use { input ->
                    tmpFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                break
            } finally {
                conn.disconnect()
            }
        }

        if (!tmpFile.isFile || tmpFile.length() == 0L) {
            throw org.gradle.api.GradleException("Downloaded HMKPM artifact is empty: ${downloadUrl.get()}")
        }
        Files.move(tmpFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

val hmkpmVersion = libs.versions.hmkpm.get()
val hmkpmAssetName = "hmkpm.kpm"
val downloadHmkpm = tasks.register<DownloadHmkpmTask>("downloadHmkpm") {
    description = "Downloads pinned HMKPM v$hmkpmVersion module from GitHub Releases into generated assets"
    group = "hmkpm"
    version.set(hmkpmVersion)
    assetFileName.set(hmkpmAssetName)
    downloadUrl.set("https://github.com/Yervant7/HuntMemory-KPM/releases/download/$hmkpmVersion/$hmkpmAssetName")
    localFilePath.set(providers.gradleProperty("hmkpmLocalFile"))
    outputDir.set(layout.buildDirectory.dir("generated/hmkpm/assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            downloadHmkpm,
            DownloadHmkpmTask::outputDir
        )
    }
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