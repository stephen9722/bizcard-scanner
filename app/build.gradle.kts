plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val signingStorePath = System.getenv("BIZCARD_SIGNING_STORE_FILE")
    ?: providers.gradleProperty("BIZCARD_SIGNING_STORE_FILE").orNull
val signingStorePassword = System.getenv("BIZCARD_KEYSTORE_PASSWORD")
    ?: providers.gradleProperty("BIZCARD_KEYSTORE_PASSWORD").orNull
val signingKeyAlias = System.getenv("BIZCARD_KEY_ALIAS")
    ?: providers.gradleProperty("BIZCARD_KEY_ALIAS").orNull
val signingKeyPassword = System.getenv("BIZCARD_KEY_PASSWORD")
    ?: providers.gradleProperty("BIZCARD_KEY_PASSWORD").orNull

android {
    namespace = "tw.pentamaster.bizcard"
    compileSdk = 36

    defaultConfig {
        applicationId = "tw.pentamaster.bizcard"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.4.0-beta"
    }

    val betaSigning = if (
        signingStorePath != null &&
        signingStorePassword != null &&
        signingKeyAlias != null &&
        signingKeyPassword != null
    ) {
        signingConfigs.create("beta") {
            storeFile = file(signingStorePath)
            storePassword = signingStorePassword
            keyAlias = signingKeyAlias
            keyPassword = signingKeyPassword
        }
    } else {
        null
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            betaSigning?.let { signingConfig = it }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    implementation("io.coil-kt:coil-compose:2.6.0")

    implementation("androidx.exifinterface:exifinterface:1.3.7")

    testImplementation("junit:junit:4.13.2")
    // Android's local-unit-test android.jar contains a throwing org.json stub.
    // Use the real JVM implementation only for tests so backup compatibility can be verified.
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
