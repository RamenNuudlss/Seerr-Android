plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version comes from the git tag in CI (e.g. v1.2.0 -> 1.2.0), falls back for local builds.
val appVersionName: String = (System.getenv("APP_VERSION") ?: "1.0.0").removePrefix("v")
val appVersionCode: Int = appVersionName.split(".").map { it.filter(Char::isDigit).ifEmpty { "0" }.toInt() }
    .let { (it + listOf(0, 0, 0)).take(3) }
    .let { (maj, min, patch) -> maj * 10000 + min * 100 + patch }
    .coerceAtLeast(1)

android {
    namespace = "io.github.ramennuudlss.seerr"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.ramennuudlss.seerr"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "GITHUB_REPO", "\"RamenNuudlss/seerr-android\"")
    }

    signingConfigs {
        create("release") {
            val ksFile = System.getenv("SIGNING_KEYSTORE_PATH")
            if (ksFile != null && file(ksFile).exists()) {
                storeFile = file(ksFile)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val release = signingConfigs.getByName("release")
            signingConfig = if (release.storeFile != null) release else signingConfigs.getByName("debug")
        }
        debug {
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        // Don't let lint warnings block CI release builds.
        checkReleaseBuilds = false
        abortOnError = false
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}
