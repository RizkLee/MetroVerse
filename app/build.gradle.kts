import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.file.ArchiveOperations
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.Properties
import javax.inject.Inject

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

val releaseKeystoreProperties = Properties()
val releaseKeystorePropertiesFile = rootProject.file("keystore.properties")
if (releaseKeystorePropertiesFile.exists()) {
    releaseKeystoreProperties.load(releaseKeystorePropertiesFile.inputStream())
}
val releaseStoreFilePath = System.getenv("METROVERSE_KEYSTORE_PATH")
    ?: releaseKeystoreProperties.getProperty("storeFile")
val releaseStorePassword = System.getenv("METROVERSE_KEYSTORE_PASSWORD")
    ?: releaseKeystoreProperties.getProperty("storePassword")
val releaseKeyAlias = System.getenv("METROVERSE_KEY_ALIAS")
    ?: releaseKeystoreProperties.getProperty("keyAlias")
val releaseKeyPassword = System.getenv("METROVERSE_KEY_PASSWORD")
    ?: releaseKeystoreProperties.getProperty("keyPassword")
val releaseSigningConfigured = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

val baseApplicationId = "com.rizklee.metroverse"
val applicationIdOverride = System.getenv("METROLIST_APPLICATION_ID")?.takeIf { it.isNotBlank() }
val appNameOverride = System.getenv("METROLIST_APP_NAME")?.takeIf { it.isNotBlank() }
val debugKeystorePathOverride = System.getenv("METROLIST_DEBUG_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val debugKeystorePassword = System.getenv("METROLIST_DEBUG_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "android"
val debugKeyAlias = System.getenv("METROLIST_DEBUG_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "androiddebugkey"
val debugKeyPassword = System.getenv("METROLIST_DEBUG_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: "android"
val persistentDebugKeystoreFile = file("persistent-debug.keystore")
val workflowDebugKeystoreFile = debugKeystorePathOverride?.let(::file)

plugins {
    id("com.android.application")
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

abstract class GenerateProtoTask : DefaultTask() {
    @get:Input
    abstract val protocUrl: Property<String>

    @get:InputFile
    abstract val protoSourceFile: RegularFileProperty

    @get:Internal
    abstract val generatedSourcesDir: DirectoryProperty

    @get:Internal
    abstract val protocExecutable: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun generate() {
        val protoFile = protoSourceFile.get().asFile
        val outputDir = generatedSourcesDir.get().asFile
        val protocFile = protocExecutable.get().asFile

        outputDir.mkdirs()

        if (!protocFile.exists() || protocFile.length() == 0L) {
            val url = protocUrl.get()
            logger.lifecycle("Downloading protoc ${url.substringAfterLast('/')} from $url")
            protocFile.parentFile.mkdirs()
            val connection = URL(url).openConnection() as java.net.HttpURLConnection
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw GradleException("Failed to download protoc: Server returned HTTP response code $responseCode for URL: $url")
            }
            connection.inputStream.use { input ->
                protocFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            protocFile.setExecutable(true)
        }

        logger.lifecycle("Generating protobuf files in $outputDir")
        execOperations.exec {
            executable = protocFile.absolutePath
            args(
                "--java_out=lite:$outputDir",
                "--kotlin_out=$outputDir",
                "-I=${protoFile.parentFile}",
                protoFile.absolutePath,
            )
        }
        logger.lifecycle("Protobuf files generated successfully")
    }
}

abstract class VerifyMoonshineNativeTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val moonshineLibrary: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val onnxruntimeLibrary: RegularFileProperty

    @get:Input
    abstract val expectedMoonshineSha256: Property<String>

    @get:Input
    abstract val expectedOnnxruntimeSha256: Property<String>

    @get:Input
    abstract val forbiddenKleidiaiSha256: Property<String>

    @get:OutputFile
    abstract val verificationStamp: RegularFileProperty

    @TaskAction
    fun verify() {
        verifySha256(
            file = moonshineLibrary.get().asFile,
            expected = expectedMoonshineSha256.get(),
            forbidden = null,
        )
        verifySha256(
            file = onnxruntimeLibrary.get().asFile,
            expected = expectedOnnxruntimeSha256.get(),
            forbidden = forbiddenKleidiaiSha256.get(),
        )
        val stamp = verificationStamp.get().asFile
        stamp.parentFile.mkdirs()
        stamp.writeText("verified\n")
    }

    private fun verifySha256(file: File, expected: String, forbidden: String?) {
        if (!file.isFile) {
            throw GradleException("Missing native library: ${file.absolutePath}")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (forbidden != null && actual.equals(forbidden, ignoreCase = true)) {
            throw GradleException(
                "Refusing KleidiAI-enabled ONNX Runtime library ${file.name} ($actual); " +
                    "expected --no_kleidiai build ${expected.lowercase()}."
            )
        }
        if (!actual.equals(expected, ignoreCase = true)) {
            throw GradleException(
                "Unexpected SHA-256 for ${file.name}: expected ${expected.lowercase()}, actual $actual"
            )
        }
    }
}

val moonshineRuntime by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
abstract class NativeArchiveAccess {
    @get:Inject abstract val archives: ArchiveOperations
}

val moonshineNativeDir = layout.buildDirectory.dir("moonshine-native")

val prepareMoonshineNative by tasks.registering(Sync::class) {
    val archives = objects.newInstance<NativeArchiveAccess>().archives
    from(moonshineRuntime.elements.map { artifacts -> artifacts.map { archives.zipTree(it.asFile) } }) {
        // Use upstream libmoonshine.so only; libonnxruntime.so comes from our --no_kleidiai v1.23.2 build.
        include("jni/arm64-v8a/libmoonshine.so")
    }
    from(layout.projectDirectory.dir("src/moonshine/ort")) {
        include("jni/arm64-v8a/libonnxruntime.so")
    }
    into(moonshineNativeDir)
}

val verifyMoonshineNative by tasks.registering(VerifyMoonshineNativeTask::class) {
    dependsOn(prepareMoonshineNative)
    moonshineLibrary.set(moonshineNativeDir.map { it.file("jni/arm64-v8a/libmoonshine.so") })
    onnxruntimeLibrary.set(moonshineNativeDir.map { it.file("jni/arm64-v8a/libonnxruntime.so") })
    expectedMoonshineSha256.set("922eddb6a0316f94091f40c5304db152e1b33ac4ad36db97823157dd402e19e0")
    expectedOnnxruntimeSha256.set("8cad9eba519db70b9da8ff6d31044ff48dd02d939dba90bc557d0f467e955464")
    forbiddenKleidiaiSha256.set("86e85022fc539963177e478c8e9a8a8219bc3b0d7515bf5297a62986dbc45e7a")
    verificationStamp.set(layout.buildDirectory.file("moonshine-native-verified.stamp"))
}

tasks.named("preBuild") {
    dependsOn(verifyMoonshineNative)
}
tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
        dependsOn(verifyMoonshineNative)
    }
}

android {
    namespace = "com.metrolist.music"
    compileSdk = 37
    sourceSets.getByName("main").kotlin.srcDir("src/moonshine/kotlin")
    sourceSets.getByName("main").assets.srcDir("src/moonshine/assets")
    ndkVersion = "28.2.13676358"
    // AGP packages the imported CMake libraries; listing them as jniLibs too would duplicate them.
    externalNativeBuild.cmake {
        path = file("src/moonshine/cpp/CMakeLists.txt")
        version = "3.22.1"
    }
    defaultConfig.externalNativeBuild.cmake {
        arguments("-DMOONSHINE_NATIVE_DIR=${layout.buildDirectory.dir("moonshine-native/jni").get().asFile.absolutePath.replace('\\', '/')}")
        arguments("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
        arguments("-DANDROID_STL=c++_shared")
    }

    defaultConfig {
        applicationId = applicationIdOverride ?: baseApplicationId
        minSdk = 26
        targetSdk = 36
        versionCode = 22
        versionName = "1.3.0"
        resValue("string", "app_name", appNameOverride ?: "MetroVerse")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        ndk {
            // The on-device recognizer uses the supported 64-bit ARM runtime.
            abiFilters += listOf("arm64-v8a")
        }

        // LastFM API keys from GitHub Secrets
        val lastFmKey = localProperties.getProperty("LASTFM_API_KEY") ?: System.getenv("LASTFM_API_KEY") ?: ""
        val lastFmSecret = localProperties.getProperty("LASTFM_SECRET") ?: System.getenv("LASTFM_SECRET") ?: ""

        buildConfigField("String", "LASTFM_API_KEY", "\"$lastFmKey\"")
        buildConfigField("String", "LASTFM_SECRET", "\"$lastFmSecret\"")
        buildConfigField("String", "ARCHITECTURE", "\"universal\"")
        buildConfigField("Long", "DISCORD_APP_ID", "1447278780795064401L")
    }

    flavorDimensions += listOf("variant")
    productFlavors {
        // FOSS - no Google Cast; updates are delivered from GitHub Releases.
        create("foss") {
            dimension = "variant"
            isDefault = true
            buildConfigField("Boolean", "CAST_AVAILABLE", "false")
            buildConfigField("Boolean", "UPDATER_AVAILABLE", "true")
            buildConfigField("String", "DISTRIBUTION", "\"foss\"")
        }

        // GMS - includes Google Cast and uses the matching GitHub Release APK.
        create("gms") {
            dimension = "variant"
            buildConfigField("Boolean", "CAST_AVAILABLE", "true")
            buildConfigField("Boolean", "UPDATER_AVAILABLE", "true")
            buildConfigField("String", "DISTRIBUTION", "\"gms\"")
        }
    }

    signingConfigs {
        create("persistentDebug") {
            storeFile = persistentDebugKeystoreFile
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("workflowDebug") {
            storeFile = workflowDebugKeystoreFile ?: persistentDebugKeystoreFile
            storePassword = debugKeystorePassword
            keyAlias = debugKeyAlias
            keyPassword = debugKeyPassword
        }
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseStoreFilePath))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
        getByName("debug") {
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storePassword = "android"
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("release")
            isShrinkResources = true
            isCrunchPngs = false
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            if (applicationIdOverride == null) {
                applicationIdSuffix = ".debug"
            }
            isDebuggable = true
            if (appNameOverride == null) {
                resValue("string", "app_name", "MetroVerse Debug")
            }
            signingConfig =
                if (workflowDebugKeystoreFile != null) {
                    signingConfigs.getByName("workflowDebug")
                } else if (persistentDebugKeystoreFile.exists()) {
                    signingConfigs.getByName("persistentDebug")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        lintConfig = file("lint.xml")
        warningsAsErrors = false
        abortOnError = false
        checkDependencies = false
        // Lint never gated anything here (abortOnError = false), so the
        // lintVital pass that assembleRelease implicitly triggers was pure
        // build time. Run lint on demand with ./gradlew :app:lintGmsRelease.
        checkReleaseBuilds = false
    }

    androidResources {
        generateLocaleConfig = true
    }

    packaging {
        jniLibs {
            // Compress the large ML runtime libs to keep the APK size reasonable.
            useLegacyPackaging = true
            keepDebugSymbols +=
                listOf(
                    "**/libandroidx.graphics.path.so",
                    "**/libdatastore_shared_counter.so",
                )
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/CONTRIBUTORS.md"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

val protocVersion = libs.versions.protobuf.get()

fun getProtocUrl(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()

    val osName = when {
        os.contains("linux") -> "linux"
        os.contains("mac") || os.contains("darwin") -> "osx"
        os.contains("windows") -> "windows"
        else -> "linux"
    }

    val archName = when {
        arch.contains("x86_64") || arch.contains("amd64") -> "x86_64"
        arch.contains("aarch64") || arch.contains("arm64") -> "aarch_64"
        arch.contains("x86") -> "x86_32"
        else -> "x86_64"
    }

    return "https://repo1.maven.org/maven2/com/google/protobuf/protoc/$protocVersion/protoc-$protocVersion-$osName-$archName.exe"
}

val protoDir = rootProject.file("metroproto")
val protoFile = protoDir.resolve("listentogether.proto")

val generateProto = if (protoFile.exists()) {
    val protocUrl = getProtocUrl()
    val protocFileName = URL(protocUrl).path.substringAfterLast('/')

    tasks.register<GenerateProtoTask>("generateProto") {
        group = "build"
        description = "Generate Kotlin protobuf files"

        protoSourceFile.set(protoFile)
        generatedSourcesDir.set(file("src/main/java"))
        this.protocUrl.set(protocUrl)
        protocExecutable.set(layout.buildDirectory.file("protoc/$protocFileName"))
    }
} else {
    logger.warn("Proto file not found at $protoFile. Skipping protobuf generation.")
    null
}

tasks.configureEach {
    if (name.startsWith("compile") || name.startsWith("assemble")) {
        generateProto?.let { dependsOn(it) }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
        )
        suppressWarnings.set(false)
    }
}

// Android provides org.json as a platform API (/apex/com.android.art/javalib/core-libart.jar).
// The standalone org.json:json artefact bundles an older Apache Harmony copy of JSONArray that
// contains an internal `myArrayList` field absent from the platform class.  Without obfuscation
// R8 inlines against this internal field; at runtime the platform class is resolved instead,
// producing a NoSuchFieldError.  Excluding the artefact globally ensures only the platform
// class is ever referenced.
configurations.configureEach {
    exclude(group = "org.json", module = "json")
}

dependencies {
    implementation(libs.guava)
    implementation(libs.coroutines.guava)
    implementation(libs.concurrent.futures)

    implementation(libs.activity)
    implementation(libs.hilt.navigation)
    implementation(libs.datastore)

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.ui.tooling)
    implementation(libs.compose.animation)
    implementation(libs.compose.reorderable)

    implementation(libs.viewmodel)
    implementation(libs.viewmodel.compose)
    implementation(libs.lifecycle.process)

    implementation(libs.material3)
    implementation(libs.palette)
    implementation(libs.materialKolor)

    implementation(libs.appcompat)

    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    implementation(libs.browser)

    implementation(libs.ucrop)

    implementation(libs.shimmer)

    implementation(libs.media3)
    implementation(libs.media3.session)
    implementation(libs.media3.okhttp)
    implementation(libs.media3.hls)

    // Google Cast - only included in GMS flavor (not available in F-Droid/FOSS builds)
    "gmsImplementation"(libs.media3.cast)
    "gmsImplementation"(libs.mediarouter)
    "gmsImplementation"(libs.cast.framework)

    implementation(libs.room.runtime)
    implementation(libs.kuromoji.ipadic)
    implementation(libs.tinypinyin)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    implementation(libs.apache.lang3)

    implementation(libs.hilt)
    implementation(libs.jsoup)
    implementation(libs.rssparser)

    add(moonshineRuntime.name, "ai.moonshine:moonshine-voice:0.1.5@aar")
    ksp(libs.hilt.compiler)

    implementation(project(":innertube"))
    implementation(project(":kugou"))
    implementation(project(":lrclib"))
    implementation(project(":lastfm"))
    implementation(project(":betterlyrics"))
    implementation(project(":shazamkit"))
    implementation(project(":paxsenix"))

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.encoding)
    implementation(libs.ktor.serialization.json)

    // Protobuf for message serialization (lite version for Android)
    implementation(libs.protobuf.javalite)
    implementation(libs.protobuf.kotlin.lite)

    coreLibraryDesugaring(libs.desugaring)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.mockwebserver)
    // XmlPullParser implementation for JVM tests of the podcast transcript feed parser.
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
