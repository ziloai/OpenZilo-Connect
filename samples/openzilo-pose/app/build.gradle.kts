plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.openzilo.pose"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.openzilo.pose"
        minSdk = 31
        targetSdk = 37
        versionCode = providers.gradleProperty("poseVersionCode").get().toInt()
        versionName = providers.gradleProperty("poseVersion").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val miuixVersion = "0.9.5-beta01"

dependencies {
    implementation("io.github.ziloai:connect-api:0.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.compose.foundation:foundation:1.12.2")
    implementation("org.jetbrains.compose.ui:ui:1.12.2")
    implementation("org.jetbrains.compose.ui:ui-tooling-preview:1.12.2")
    debugImplementation("org.jetbrains.compose.ui:ui-tooling:1.12.2")
    implementation("top.yukonga.miuix.kmp:miuix-ui:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-glass:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-preference:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-glass-icons:$miuixVersion")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
