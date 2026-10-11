plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val rustMediaJniDirectory = layout.buildDirectory.dir("generated/rustJni")

android {
    namespace = "dev.jonalakas.bridgepad"
    ndkVersion = "28.2.13676358"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.jonalakas.bridgepad"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    sourceSets.getByName("main").jniLibs.directories.add(
        rustMediaJniDirectory.get().asFile.absolutePath,
    )
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":protocol"))
    implementation(project(":streaming-core"))
    implementation(project(":transport-bluetooth-hid"))
    implementation(project(":transport-bluetooth-desktop"))
    implementation(project(":transport-network"))
    implementation(project(":transport-usb-accessory"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.webrtc.sdk.android)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

val buildRustMedia by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the arm64 Android Media v1 JNI library with cargo-ndk"
    val manifest = rootProject.file("native/bridgepad-android-media/Cargo.toml")
    val outputDirectory = rustMediaJniDirectory.get().asFile
    val cargoTargetDirectory = rootProject.layout.buildDirectory
        .dir("rust-target/android-media")
        .get()
        .asFile
    inputs.files(
        manifest,
        rootProject.file("native/bridgepad-android-media/Cargo.lock"),
    )
    inputs.dir(rootProject.file("native/bridgepad-android-media/src"))
    inputs.dir(rootProject.file("desktop/crates/bridgepad-media/src"))
    inputs.dir(rootProject.file("desktop/crates/bridgepad-media-protocol/src"))
    outputs.dir(outputDirectory)
    workingDir(manifest.parentFile)
    environment("CARGO_TARGET_DIR", cargoTargetDirectory.absolutePath)
    doFirst {
        delete(outputDirectory)
        outputDirectory.mkdirs()
    }
    commandLine(
        "cargo",
        "ndk",
        "-t",
        "arm64-v8a",
        "-P",
        "28",
        "-o",
        outputDirectory.absolutePath,
        "build",
        "--release",
        "--locked",
        "--lib",
    )
}

tasks.named("preBuild").configure {
    dependsOn(buildRustMedia)
}
