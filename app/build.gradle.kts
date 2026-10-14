import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// On-device speech stack (sherpa-onnx). Downloaded at build time from the k2-fsa GitHub
// releases, verified against these SHA-256 pins, and bundled into the APK; nothing large
// is committed to git. Bump a URL and its hash together.
val sherpaRelease = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
val speechPins = mapOf(
    // Native library (+ Kotlin API) with ONNX Runtime statically linked in. Apache-2.0.
    "$sherpaRelease/v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar" to
        "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471",
    // Moonshine tiny English v2, quantized (~44 MB unpacked). MIT.
    "$sherpaRelease/asr-models/sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27.tar.bz2" to
        "9ec31b342d8fa3240c3b81b8f82e1cf7e3ac467c93ca5a999b741d5887164f8d",
    // Silero voice activity detector (0.6 MB). MIT.
    "$sherpaRelease/asr-models/silero_vad.onnx" to
        "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
    // NeMo SpeakerNet speaker embeddings (23 MB); best of the small models in evaluation.
    "$sherpaRelease/speaker-recongition-models/nemo_en_speakerverification_speakernet.onnx" to
        "d204dc8aac0014b8543f05fc8e310510c7022bc65b6452c203ec205ef7a66b23",
)

/** Downloads [pins] (cached across `clean`), verifies them, and lays out assets + the AAR. */
abstract class FetchSpeechModels @Inject constructor(
    private val archives: ArchiveOperations,
    private val fs: FileSystemOperations,
) : DefaultTask() {
    @get:Input abstract val pins: MapProperty<String, String>
    @get:Internal abstract val cacheDir: DirectoryProperty
    @get:OutputDirectory abstract val assetsDir: DirectoryProperty
    @get:OutputFile abstract val aarFile: RegularFileProperty

    @TaskAction
    fun fetch() {
        val cache = cacheDir.get().asFile.apply { mkdirs() }
        fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
            .joinToString("") { "%02x".format(it) }
        fun obtain(suffix: String): File {
            val (url, hash) = pins.get().entries.single { it.key.endsWith(suffix) }
            val file = File(cache, url.substringAfterLast('/'))
            if (file.exists() && sha256(file) == hash) return file
            logger.lifecycle("Downloading ${file.name}")
            val partial = File(cache, file.name + ".part")
            URI(url).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            val actual = sha256(partial)
            if (actual != hash) {
                partial.delete()
                throw GradleException("Checksum mismatch for ${file.name}: expected $hash, got $actual")
            }
            partial.renameTo(file)
            return file
        }

        val models = assetsDir.get().dir("models").asFile
        fs.delete { delete(models) }
        fs.copy {
            from(archives.tarTree(archives.bzip2(obtain("moonshine-tiny-en-quantized-2026-02-27.tar.bz2")))) {
                include("**/encoder_model.ort", "**/decoder_model_merged.ort", "**/tokens.txt", "**/LICENSE")
                eachFile { path = "moonshine/$name" }
            }
            includeEmptyDirs = false
            into(models)
        }
        fs.copy {
            from(obtain("silero_vad.onnx"), obtain("nemo_en_speakerverification_speakernet.onnx"))
            into(models)
        }
        fs.copy {
            from(obtain(".aar"))
            into(aarFile.get().asFile.parentFile)
            rename { aarFile.get().asFile.name }
        }
    }
}

val fetchSpeechModels = tasks.register<FetchSpeechModels>("fetchSpeechModels") {
    pins.set(speechPins)
    cacheDir.set(rootProject.layout.projectDirectory.dir(".gradle/speech-models"))
    assetsDir.set(layout.buildDirectory.dir("generated/speechAssets"))
    aarFile.set(layout.buildDirectory.file("generated/speechLibs/sherpa-onnx.aar"))
}

// Firebase config is per-developer and not committed (see README). Without it the app
// still builds; the Conversation tab then explains that Firebase isn't set up.
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

android {
    namespace = "com.example.avifone"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.example.avifone"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                // Oboe is distributed as a prefab package built against the shared STL.
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildTypes {
        debug {
            // x86_64 only so debug builds and instrumented tests run on the emulator.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            // Phones are arm64; leaving out x86_64 saves ~29 MB of native code.
            ndk { abiFilters += listOf("arm64-v8a") }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Optimized build (native DSP at -O2+) signed with the local debug key so it can
            // be side-loaded. Replace with your own release keystore before publishing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    lint {
        // Release builds are deliberately arm64-only (phones); Chromebooks aren't a target.
        disable += "ChromeOsAbiSupport"
    }

    androidResources {
        // Speech models are memory-mapped straight out of the APK.
        noCompress += listOf("onnx", "ort")
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(fetchSpeechModels, FetchSpeechModels::assetsDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.oboe)
    implementation(files(fetchSpeechModels.flatMap { it.aarFile }))
    // On-device summary: LiteRT-LM runs a small LLM downloaded on first use; ML Kit gives
    // access to Gemini Nano on phones that have it.
    implementation(libs.litertlm.android)
    implementation(libs.mlkit.genai.prompt)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.ai)
    implementation(libs.firebase.auth)
    // App Check is mandatory for Firebase AI Logic. Side-loaded builds use the debug
    // provider with per-install tokens registered in the console (see AviFoneApp).
    implementation(libs.firebase.appcheck.debug)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.kotlinx.coroutines.play.services)
    testImplementation(libs.junit)
    // Android's org.json is a stub in local unit tests; use the real library there.
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
