import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// The release key lives outside the repo. Every published update must be signed
// with it, or Android refuses to install it over the copy people already have.
val keystoreProps: Map<String, String> = Properties().let { props ->
    val f = file(providers.gradleProperty("symusKeystore").getOrElse("../../symus-release-keys/keystore.properties"))
    if (f.exists()) f.inputStream().use { props.load(it) }
    props.stringPropertyNames().associateWith { props.getProperty(it) }
}

android {
    namespace = "io.github.lectorsys.symus"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.lectorsys.symus"
        // 26 is what NewPipeExtractor and Media3 want without contortions, and
        // it still covers Android 8 and up.
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Schemas are checked in: a migration is only reviewable against the
        // shape it is migrating from.
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }

    }

    /**
     * Two builds, because the inference runtime decides what can be installed.
     *
     * MediaPipe's GenAI task ships prebuilt `.so` files for `arm64-v8a` and
     * `x86_64` only. An APK that contains native libraries and none matching the
     * installing device's ABI is refused by the package manager outright
     * (`INSTALL_FAILED_NO_MATCHING_ABIS`) — it does not install and then run
     * without the feature. So a single APK carrying MediaPipe cannot be
     * installed on a 32-bit phone at all, whatever the app would have done about
     * it at runtime.
     *
     * `full` is that APK, for 64-bit devices. `lite` omits the dependency
     * entirely — no MediaPipe classes, no 64-bit-only libraries, no ABI filter —
     * so it installs anywhere the minSdk allows and every other feature behaves
     * identically. `LlmEngine` is the one file that differs between them.
     */
    flavorDimensions += "runtime"
    productFlavors {
        create("full") {
            dimension = "runtime"
            // Filtered to what MediaPipe actually ships. Without this the build
            // would package armeabi-v7a and x86 copies of Compose's small
            // libraries and claim an ABI support the inference runtime cannot
            // honour.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        create("lite") {
            dimension = "runtime"
            // Deliberately no abiFilters: every ABI the remaining native
            // dependencies publish is packaged, which is what makes this the
            // installable build on 32-bit hardware.
            versionNameSuffix = "-lite"
        }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) create("release") {
            storeFile = file(keystoreProps.getValue("storeFile"))
            storePassword = keystoreProps.getValue("storePassword")
            keyAlias = keystoreProps.getValue("keyAlias")
            keyPassword = keystoreProps.getValue("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // R8 on, because a sideloaded APK has no store to shrink it for us —
            // and because the extractor's ProGuard rules only get exercised in a
            // minified build. Testing that only at release time is how "works in
            // debug, crashes in release" happens.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Note on testing the minified build: `testBuildType = "release"` does not
    // work here and is deliberately not configured. R8 renames the app's own
    // classes — `LlmRuntime` and `ChatAgent` do not appear in the release dex
    // at all — so an instrumentation APK compiled against those names cannot
    // resolve them, the test class fails to load, and the run reports zero
    // tests and a green build. Keeping the app's names to work around that
    // would mean measuring something other than what ships.
    //
    // The minified build is therefore exercised through its own UI, against the
    // real media session, rather than through instrumentation.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor uses java.time and the stream APIs, which do not exist
        // on API 26. Desugaring is what makes minSdk 26 possible at all here.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }

    // The migration test validates the result against the exported schemas, so
    // they have to be on the device with it.
    sourceSets.getByName("androidTest") {
        assets.srcDirs(files("$projectDir/schemas"))
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// The live extractor test is opt-in; this is what lets -Dsymus.live=1 through
// to the test JVM, which does not inherit Gradle's system properties.
tasks.withType<Test>().configureEach {
    systemProperty("symus.live", providers.systemProperty("symus.live").getOrElse("0"))
    testLogging { events("passed", "skipped", "failed") }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)

    implementation(libs.newpipe.extractor)

    // `full` only. This single line is what the whole flavour split is about:
    // the `lite` APK never sees these classes or their native libraries.
    "fullImplementation"(libs.mediapipe.genai)

    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // The database tests run on a device: Room's in-memory builder and its
    // migration helper both need a real SQLite and a real Context, and a
    // stand-in for either would be testing the stand-in.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
