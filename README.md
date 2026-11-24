# OpenZilo Connect

OpenZilo Connect is the Android companion and connection broker for OpenZilo rings. It owns the Bluetooth connection and grants other Android apps access to specific ring capabilities. Speech recognition and model management belong to the client, not Connect.

## Build from source

Use JDK 21, Git, and Android command-line tools. Set `ANDROID_HOME` to the Android SDK path. The Gradle wrapper builds the app and downloads a fixed libopenzilo Android AAR from GitHub Releases and miuix from Maven Central. No submodules, Rust toolchain, or NDK installation is needed.

```bash
git clone https://github.com/ziloai/OpenZilo-Connect.git
cd OpenZilo-Connect
sdkmanager 'platforms;android-37.0' 'platforms;android-37.2' 'build-tools;37.0.0'
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. Android 8.0 (API 26) or newer is required. The debug APK uses a local debug signing key and is for development, not a signed production release.

## Android app integration

The [`connect-api`](connect-api) library contains the Binder client and AIDL contract. An app can build against that library without compiling the Bluetooth or Rust implementation. [OpenZilo Input](https://github.com/ziloai/OpenZilo-Input) is a working client.

1. Install Connect and connect a ring from its UI.
2. Declare package visibility for `com.openzilo.connect` in the client manifest, then bind using `ConnectClient.connect()`.
3. Use `requestAuthorization()` for the scopes needed by your app and open the returned authorization intent with user interaction. Check authorization before invoking protected operations. Devices on Android 12 or newer may require the user to grant Nearby devices permission to Connect itself.
4. Compare the service’s `apiVersion` with `ConnectClient.API_VERSION`. Subscribe to events before enabling live audio, and close the client when it is no longer needed.

The Android client AAR is distributed via [GitHub Releases](https://github.com/ziloai/OpenZilo-Connect/releases). To resolve it without a source checkout or token, add this to the repositories in your `settings.gradle.kts`:

```kotlin
ivy {
    url = uri("https://github.com/ziloai/OpenZilo-Connect/releases/download")
    patternLayout {
        artifact("v[revision]/[artifact]-[revision].[ext]")
        artifact("android-v[revision]/[artifact]-[revision].[ext]")
    }
    metadataSources { artifact() }
    content { includeModule("io.github.ziloai", "connect-api") }
}
```

In your Android module's dependencies, add:

```kotlin
implementation("io.github.ziloai:connect-api:0.1.0@aar")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
```

Use `compileSdk` 37 or newer and a compiler that can read Kotlin 2.4 metadata; Connect and Input use Kotlin 2.4.21. The AAR is not an installable app. Install a compatible version of Connect on the device. There is no published Maven Central artifact or desktop/iOS host in this repository yet.

## Releases

The `android-release.yml` workflow publishes the client AAR, an installable debug-signed APK, and an unsigned release AAB together. Push a `vX.Y.Z` tag matching `connectVersion` in `gradle.properties` to trigger it.

## Community

- WeChat developer group

  <img src="docs/assets/wechat-group.png" alt="OpenZilo WeChat developer group QR code" width="160">

- [Discord](https://discord.gg/pTJZ3RMHA5)
- [GitHub Discussions](https://github.com/ziloai/OpenZilo/discussions)

## License

Connect is licensed under [MPL-2.0](LICENSE). It builds against a pinned [libopenzilo Android release](https://github.com/ziloai/libopenzilo/releases) (MPL-2.0) and published [miuix](https://github.com/compose-miuix-ui/miuix) packages (Apache-2.0). License copies and corresponding-source information are bundled in `assets/licenses/` inside the APK.
