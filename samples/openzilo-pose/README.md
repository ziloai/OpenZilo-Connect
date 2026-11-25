# OpenZilo Pose

Standalone Android 3D attitude modeling client for OpenZilo rings, built on `io.github.ziloai:connect-api:0.1.2@aar` (`API_VERSION = 2`), Jetpack Compose + Miuix Glass, and an OpenGL ES 3.2 4x-MSAA PBR renderer modeled from [`ziloai/openzilo-hardware`](https://github.com/ziloai/openzilo-hardware) (`10_asm.stp`).

It resolves `connect-api` purely as a published AAR binary without depending on `OpenZilo-Connect` or `libopenzilo` source code.

## Build

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk` (`minSdk 31`, `targetSdk 37`, `compileSdk 37.2`).
