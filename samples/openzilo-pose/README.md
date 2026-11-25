# OpenZilo Pose

Standalone Android 3D attitude modeling client for OpenZilo rings, built on `:connect-api` (`API_VERSION = 2`), Jetpack Compose + Miuix Glass, and an OpenGL ES 3.2 4x-MSAA PBR renderer modeled from [`ziloai/openzilo-hardware`](https://github.com/ziloai/openzilo-hardware) (`10_asm.stp`).

When built inside `OpenZilo-Connect`, Gradle automatically substitutes `:connect-api` from the parent checkout via `includeBuild("../..")`; when copied standalone, it resolves `io.github.ziloai:connect-api` from GitHub Releases.

## Build

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk` (`minSdk 31`, `targetSdk 37`, `compileSdk 37.2`).
