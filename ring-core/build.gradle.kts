plugins { id("com.android.library") }

android {
    namespace = "com.openzilo.connect.core"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    api("io.github.ziloai:libopenzilo-android:0.1.0@aar")
    api("net.java.dev.jna:jna:5.19.1@aar")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testRuntimeOnly("net.java.dev.jna:jna:5.19.1@jar")
}
