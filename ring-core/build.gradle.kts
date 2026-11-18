import java.io.File
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins { id("com.android.library") }
val coreDir = rootDir.resolve(providers.gradleProperty("rustCoreDir").orElse(projectDir.parentFile.resolve("vendor/libopenzilo").absolutePath).get())
val nativeAbis = providers.gradleProperty("nativeAbis").orElse("arm64-v8a,x86_64")
val nativeMinApi = 26
android {
    namespace = "com.openzilo.connect.core"
    compileSdk = 37
    defaultConfig {
        minSdk = nativeMinApi
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += nativeAbis.get().split(",") }
    }
    ndkVersion = "28.2.13676358"
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    api("net.java.dev.jna:jna:5.19.1@aar")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testRuntimeOnly("net.java.dev.jna:jna:5.19.1@jar")
}
abstract class NativeBindingsTask @Inject constructor(private val processes: ExecOperations) : DefaultTask() {
    @get:InputDirectory abstract val sources: DirectoryProperty
    @get:InputFiles abstract val manifests: ConfigurableFileCollection
    @get:Input abstract val abis: Property<String>
    @get:Input abstract val hostOs: Property<String>
    @get:Input abstract val minApi: Property<Int>
    @get:Internal abstract val ndkDirectory: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val ndkMetadata: RegularFileProperty
    @get:LocalState abstract val cargoTarget: DirectoryProperty
    @get:OutputDirectory abstract val bindingsDirectory: DirectoryProperty
    @get:OutputDirectory abstract val jniDirectory: DirectoryProperty

    @TaskAction fun generate() {
        val core = sources.get().asFile.parentFile
        val target = cargoTarget.get().asFile
        val host = when (hostOs.get()) {
            "Linux" -> "linux-x86_64"
            "Mac OS X" -> "darwin-x86_64"
            else -> error("Build the native library on Linux or macOS")
        }
        val suffix = if (host.startsWith("darwin")) "dylib" else "so"
        val toolchains = ndkDirectory.dir("toolchains/llvm/prebuilt/$host/bin").get().asFile
        fun run(vararg args: String, linker: Pair<String, File>? = null) {
            processes.exec {
                workingDir(core)
                environment("CARGO_TARGET_DIR", target.absolutePath)
                linker?.let { (triple, file) ->
                    environment("CARGO_TARGET_${triple.uppercase().replace('-', '_')}_LINKER", file.absolutePath)
                }
                commandLine(args.toList())
            }
        }
        val kotlin = bindingsDirectory.get().asFile
        val jni = jniDirectory.get().asFile
        kotlin.deleteRecursively()
        jni.deleteRecursively()
        kotlin.mkdirs()
        run("cargo", "build", "--locked", "--features", "cli")
        run(target.resolve("debug/uniffi-bindgen").absolutePath, "generate",
            "--library", target.resolve("debug/liblibopenzilo.$suffix").absolutePath,
            "--language", "kotlin", "--config", core.resolve("uniffi.toml").absolutePath,
            "--out-dir", kotlin.absolutePath, "--no-format")
        for (abi in abis.get().split(',')) {
            val triple = when (abi) {
                "arm64-v8a" -> "aarch64-linux-android"
                "x86_64" -> "x86_64-linux-android"
                else -> error("Unsupported Android ABI: $abi")
            }
            val linker = toolchains.resolve("$triple${minApi.get()}-clang")
            check(linker.isFile) { "Missing NDK linker: $linker" }
            run("cargo", "build", "--locked", "--release", "--lib", "--target", triple, linker = triple to linker)
            target.resolve("$triple/release/liblibopenzilo.so").copyTo(jni.resolve("$abi/liblibopenzilo.so"))
        }
    }
}
val generateNative = tasks.register<NativeBindingsTask>("generateNative") {
    sources.set(coreDir.resolve("src"))
    manifests.from(coreDir.resolve("Cargo.toml"), coreDir.resolve("Cargo.lock"), coreDir.resolve("build.rs"),
        coreDir.resolve("uniffi.toml"), rootDir.resolve("rust-toolchain.toml"))
    abis.set(nativeAbis)
    hostOs.set(providers.systemProperty("os.name"))
    minApi.set(nativeMinApi)
    ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
    ndkMetadata.set(ndkDirectory.file("source.properties"))
    cargoTarget.set(layout.buildDirectory.dir("cargo"))
    bindingsDirectory.set(layout.buildDirectory.dir("generated/uniffi"))
    jniDirectory.set(layout.buildDirectory.dir("generated/jniLibs"))
}
androidComponents.onVariants { variant ->
    variant.sources.kotlin?.addGeneratedSourceDirectory(generateNative, NativeBindingsTask::bindingsDirectory)
    variant.sources.jniLibs?.addGeneratedSourceDirectory(generateNative, NativeBindingsTask::jniDirectory)
}
