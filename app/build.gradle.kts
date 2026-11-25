plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firebase sync turns on once app/google-services.json (from the Firebase console) is in the repo.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// GitHub's build number becomes the version (1, 2, 3...), so every build installs as an update.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.eric.todo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eric.todo"
        minSdk = 29          // Android 10+
        targetSdk = 35
        versionCode = buildNumber
        versionName = "$buildNumber"

        // Modern phones (Samsung Galaxy, Pixel) only: keeps the download small.
        ndk { abiFilters += "arm64-v8a" }
    }

    // One fixed signing key so new builds install over old ones and keep your tasks.
    signingConfigs {
        create("todo") {
            storeFile = file("todo-release.jks")
            storePassword = "todo-eric"
            keyAlias = "todo"
            keyPassword = "todo-eric"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("todo")
        }
        debug {
            signingConfig = signingConfigs.getByName("todo")
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Drag-and-drop reordering.
    implementation("sh.calvin.reorderable:reorderable:2.4.3")

    // Reads text from photos, on the phone, no internet needed.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Live sync between phone and tablet (Firebase sign-in + online database).
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
