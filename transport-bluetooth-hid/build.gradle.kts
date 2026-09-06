plugins {
    id("com.android.library")
}

android {
    namespace = "dev.jonalakas.bridgepad.transport.bluetooth.hid"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":domain"))
    testImplementation(libs.junit)
}
