plugins {
    id("com.android.application")
}

android {
    // The R class lives here; the game code itself keeps the original JavaME
    // packages ("digger" and "general.*").
    namespace = "io.github.haimav.diggerme"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.haimav.diggerme"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        // The headless game test runs the real engine on the JVM; the few
        // android.* classes it touches (MediaPlayer, AudioTrack) become no-ops.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
