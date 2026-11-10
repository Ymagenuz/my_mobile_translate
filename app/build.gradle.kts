import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Signing details stay outside the repository; see README.md.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

android {
    namespace = "com.ymagenuz.snipdotranslate"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ymagenuz.snipdotranslate"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ML Kit models are about 4 MB per script per architecture.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // For the x86_64 emulator; release APKs stay arm64-only.
            ndk { abiFilters += "x86_64" }
            buildConfigField("boolean", "ALLOW_LOCAL_HTTP", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
            buildConfigField("boolean", "ALLOW_LOCAL_HTTP", "false")
        }
        // The optimised release code, installable beside debug on an emulator, for testing.
        create("qa") {
            initWith(getByName("release"))
            applicationIdSuffix = ".qa"
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            ndk { abiFilters += "x86_64" }
            buildConfigField("boolean", "ALLOW_LOCAL_HTTP", "true")
        }
    }

    // Pure-logic tests also run on a device, where regular expressions behave differently.
    sourceSets {
        listOf("test", "androidTest").forEach { name ->
            getByName(name) {
                kotlin.srcDir("src/sharedTest/java")
                resources.srcDir("src/sharedTest/resources")
            }
        }
        getByName("qa") {
            manifest.srcFile("src/debug/AndroidManifest.xml")
            kotlin.srcDir("src/debug/java")
            res.srcDir("src/debug/res")
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
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.savedstate.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.mlkit.text.latin)
    implementation(libs.mlkit.text.chinese)
    implementation(libs.mlkit.text.japanese)
    implementation(libs.mlkit.text.korean)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
