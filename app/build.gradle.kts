plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Spotify client ID is kept out of the repository, in ~/.gradle/gradle.properties.
val spotifyClientId = providers.gradleProperty("teamrowingDisplay.spotifyClientId").orNull
    ?: error("Missing teamrowingDisplay.spotifyClientId in ~/.gradle/gradle.properties")

// The release signing key is kept out of the repository too; without it, release builds are unsigned.
val releaseKeystore = providers.gradleProperty("teamrowingDisplay.releaseKeystore").orNull
    ?.replaceFirst("~", System.getProperty("user.home"))

android {
    namespace = "de.gregoire.teamrowingdisplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "de.gregoire.teamrowingdisplay"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"teamrowing-display://callback\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.gradleProperty("teamrowingDisplay.releaseKeystorePassword").get()
                keyAlias = providers.gradleProperty("teamrowingDisplay.releaseKeyAlias").get()
                // PKCS12 keystores, keytool's default, use the same password for the key
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // Optimizes the whole app; limiting it to the libraries with packageScope made R8 produce
            // classes that fail to load (IllegalAccessError in kotlin.collections at startup)
            optimization {
                enable = true
            }
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
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
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