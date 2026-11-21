import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing, never kept in git. Read from environment variables (set
// by GitHub Actions from the repo's secrets) or from local.properties:
//   RELEASE_STORE_FILE=/path/to/key.jks
//   RELEASE_STORE_PASSWORD=...
//   RELEASE_KEY_ALIAS=...
//   RELEASE_KEY_PASSWORD=...
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() } ?: localProps.getProperty(name)
val releaseStore: String? = signingValue("RELEASE_STORE_FILE")

android {
    namespace = "com.th4rv4.zukinav"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.th4rv4.zukinav"
        minSdk = 29
        targetSdk = 35
        // CI passes -PappVersionName / -PappVersionCode from the git tag.
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("appVersionName") as String?) ?: "0.1.0"
    }

    signingConfigs {
        // A fixed debug key kept in the repo, so every build (local or
        // GitHub Actions) has the same signature and installs as an update.
        // Without it each CI runner made a fresh key and Android refused to
        // update over the previous APK.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            releaseStore?.let { path ->
                storeFile = file(path)
                storePassword = signingValue("RELEASE_STORE_PASSWORD")
                keyAlias = signingValue("RELEASE_KEY_ALIAS")
                keyPassword = signingValue("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // "Zuki Sim": a test build of the same app, installed next to the real
        // one, for testing against Zuki Dash Sim (a phone pretending to be the
        // dash). Its own name, settings, rides and log. Built locally only:
        //   ./gradlew assembleSim
        create("sim") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".sim"
            versionNameSuffix = "-sim"
        }
        release {
            // Kept readable (no shrinking or renaming): the app is small, and
            // plain code is easier for Play Protect to check.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseStore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
