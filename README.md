# OpenZilo Connect

OpenZilo Connect is the Android companion and connection broker for OpenZilo rings. It owns the Bluetooth connection and grants other Android apps access to specific ring capabilities. Speech recognition and model management belong to the client, not Connect.

## Build from source

On Linux or macOS, use JDK 21, Git, Android command-line tools, and rustup. Set `ANDROID_HOME` to the Android SDK path. The Gradle wrapper builds the app, runs Cargo and UniFFI, and downloads miuix from Maven Central.

```bash
git clone --recurse-submodules https://github.com/ziloai/OpenZilo-Connect.git
cd OpenZilo-Connect
sdkmanager 'platforms;android-37.0' 'platforms;android-37.2' 'build-tools;37.0.0' 'ndk;28.2.13676358'
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. Android 8.0 (API 26) or newer is required. The debug APK uses a local debug signing key and is for development, not a signed production release.

## Android app integration

The [`connect-api`](connect-api) library contains the Binder client and AIDL contract. An app can build against that library without compiling the Bluetooth or Rust implementation. [OpenZilo Input](https://github.com/ziloai/OpenZilo-Input) is a working client.

1. Install Connect and connect a ring from its UI.
2. Declare package visibility for `com.openzilo.connect` in the client manifest, then bind using `ConnectClient.connect()`.
3. Use `requestAuthorization()` for the scopes needed by your app and open the returned authorization intent with user interaction. Check authorization before invoking protected operations. Devices on Android 12 or newer may require the user to grant Nearby devices permission to Connect itself.
4. Compare the service’s `apiVersion` with `ConnectClient.API_VERSION`. Subscribe to events before enabling live audio, and close the client when it is no longer needed.

There is no published Maven artifact or desktop/iOS host in this repository yet.

## License

Connect is licensed under [MPL-2.0](LICENSE). It builds against the pinned [libopenzilo](https://github.com/ziloai/libopenzilo) source (MPL-2.0) and published [miuix](https://github.com/compose-miuix-ui/miuix) packages (Apache-2.0). License copies and corresponding-source information are bundled in `assets/licenses/` inside the APK.
