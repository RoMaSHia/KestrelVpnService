import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val rustCoreDir = rootProject.layout.projectDirectory.dir("rust-core")
val debugRustJniLibsDir = layout.buildDirectory.dir("generated/rustJniLibs/debug")
val releaseRustJniLibsDir = layout.buildDirectory.dir("generated/rustJniLibs/release")

val rustAbisProvider = providers.gradleProperty("rustAbis").orElse("arm64-v8a,x86_64")
val skipRustBuildProvider = providers.gradleProperty("skipRustBuild")
    .map { it.toBoolean() }
    .orElse(false)

android {
    namespace = "dev.kestrel.proxy"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dev.kestrel.proxy"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.2.3"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties()
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { keystoreProps.load(it) }
    }

    val releaseKeystorePath = System.getenv("KEYSTORE_PATH")
        ?.takeIf { it.isNotBlank() }
        ?: keystoreProps.getProperty("storeFile")
    val hasCustomKeystore = !releaseKeystorePath.isNullOrBlank() && file(releaseKeystorePath).exists()

    signingConfigs {
        create("release") {
            if (hasCustomKeystore) {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                    ?.takeIf { it.isNotBlank() }
                    ?: keystoreProps.getProperty("storePassword").orEmpty()
                keyAlias = System.getenv("KEY_ALIAS")
                    ?.takeIf { it.isNotBlank() }
                    ?: keystoreProps.getProperty("keyAlias").orEmpty()
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?.takeIf { it.isNotBlank() }
                    ?: keystoreProps.getProperty("keyPassword").orEmpty()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasCustomKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    sourceSets {
        getByName("debug") {
            jniLibs.srcDir(debugRustJniLibsDir)
        }
        getByName("release") {
            jniLibs.srcDir(releaseRustJniLibsDir)
        }
    }
}

fun registerCargoNdkTask(
    taskName: String,
    isRelease: Boolean,
    outputDir: Provider<Directory>,
) = tasks.register<Exec>(taskName) {
    group = "rust"
    description = "Builds proxy_core native shared libraries via cargo-ndk (${if (isRelease) "release" else "debug"})"

    onlyIf { !skipRustBuildProvider.get() }

    inputs.dir(rustCoreDir.dir("src"))
    inputs.file(rustCoreDir.file("Cargo.toml"))
    inputs.file(rustCoreDir.file(".cargo/config.toml"))
    val cargoLockFile = rustCoreDir.file("Cargo.lock").asFile
    if (cargoLockFile.exists()) {
        inputs.file(cargoLockFile)
    }
    inputs.property("abis", rustAbisProvider)
    inputs.property("release", isRelease)
    outputs.dir(outputDir)

    workingDir = rustCoreDir.asFile

    doFirst {
        val homeDir = System.getProperty("user.home")
        val cargoHomeBin = System.getenv("CARGO_HOME")
            ?.let { file("$it/bin") }
            ?: file("$homeDir/.cargo/bin")
        val cargoBinary = file("${cargoHomeBin.absolutePath}/cargo")
            .takeIf { it.exists() }
            ?.absolutePath
            ?: "cargo"

        val currentPath = System.getenv("PATH").orEmpty()
        environment("PATH", "${cargoHomeBin.absolutePath}:$currentPath")

        val ndkPath = android.ndkDirectory
            .takeIf { it.exists() }
            ?.absolutePath
            ?: System.getenv("ANDROID_NDK_HOME")
        if (!ndkPath.isNullOrBlank()) {
            environment("ANDROID_NDK_HOME", ndkPath)
        }

        val abis = rustAbisProvider.get()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val outDirFile = outputDir.get().asFile
        outDirFile.mkdirs()

        val cmd = mutableListOf(cargoBinary, "ndk")
        for (abi in abis) {
            cmd += listOf("-t", abi)
        }
        cmd += listOf("-P", "26", "-o", outDirFile.absolutePath, "build")
        if (isRelease) {
            cmd += "--release"
        }
        commandLine(cmd)
    }
}

val buildRustDebug = registerCargoNdkTask(
    taskName = "buildRustDebug",
    isRelease = false,
    outputDir = debugRustJniLibsDir,
)

val buildRustRelease = registerCargoNdkTask(
    taskName = "buildRustRelease",
    isRelease = true,
    outputDir = releaseRustJniLibsDir,
)

tasks.matching { it.name == "mergeDebugJniLibFolders" }.configureEach {
    dependsOn(buildRustDebug)
}

tasks.matching { it.name == "mergeReleaseJniLibFolders" }.configureEach {
    dependsOn(buildRustRelease)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
