import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.whyoolw.monopod"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.whyoolw.monopod"
        // Your own Spotify app's Client ID, from local.properties (never committed):
        //   spotify.clientId=0123456789abcdef0123456789abcdef
        // Without it, the app asks for one in Spotify → Set Up Spotify.
        val localProps = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${localProps.getProperty("spotify.clientId", "")}\"")
        minSdk = 36
        targetSdk = 37
        versionCode = 16
        versionName = "1.7.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // A personal app installed by hand: the debug key is enough. Swap in your own
            // signing config before publishing anywhere.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf("en", "ru")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    // Material style: color roles, icons, a scheme from the album cover
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.palette)
    implementation(libs.material.kolor)
    // Spotify: App Remote SDK (official release from github.com/spotify/android-sdk) drives the Spotify app
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation(libs.gson)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.extractor)
    implementation(libs.androidx.media3.inspector)

    testImplementation(libs.junit)
    // Real org.json for unit tests; the Android stub throws
    testImplementation(libs.json)
}
