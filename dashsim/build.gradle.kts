// Zuki Dash Sim: runs on a spare phone and pretends to be the Gixxer SF 250's
// dash, so Zuki Sim can be tested at home. Debug build only:
//   ./gradlew :dashsim:assembleDebug
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.th4rv4.zukidashsim"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.th4rv4.zukidashsim"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // Speaks the same frames as the app: share its protocol code.
    sourceSets["main"].java.srcDir("../app/src/main/java/com/th4rv4/zukinav/protocol")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Only ever a test tool: no release build.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
