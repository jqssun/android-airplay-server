plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

import java.util.Properties
import java.security.MessageDigest

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

val allAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
// Windows can reuse the unmodified native libraries from the matching upstream release.
// The Kotlin display/decoder changes are still compiled from this checkout.
val nativeApk = providers.gradleProperty("airplayNativeApk").orNull?.let { rootProject.file(it) }
val prebuiltJni = layout.buildDirectory.dir("upstream-jni")

android {
    namespace = "io.github.jqssun.airplay"
    compileSdk = 37
    ndkVersion = "27.0.12077973"

    if (localProps.containsKey("storeFile")) {
        signingConfigs {
            create("release") {
                storeFile = file(localProps.getProperty("storeFile"))
                storePassword = localProps.getProperty("storePassword")
                keyAlias = localProps.getProperty("keyAlias")
                keyPassword = localProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "io.github.jqssun.airplay"
        minSdk = 23
        targetSdk = 37
        versionCode = 36
        versionName = "0.0.36"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        if (nativeApk == null) externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    if (nativeApk == null) externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        debug {
            ndk { abiFilters += allAbis }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            ndk { abiFilters += allAbis }
        }
        create("sonyTest") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            matchingFallbacks += "release"
            applicationIdSuffix = ".sonytest"
            versionNameSuffix = "-sony.4"
            signingConfig = signingConfigs.getByName("debug")
        }
        create("sonyRelease") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            applicationIdSuffix = ".sonytest"
            versionNameSuffix = "-sony.4"
            signingConfig = signingConfigs.getByName("debug")
        }
        // debuggable build with HWASan (arm64) + UBSan in native code
        create("sanitize") {
            initWith(getByName("debug"))
            matchingFallbacks += "debug"
            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
            }
            externalNativeBuild {
                cmake { arguments += "-DSANITIZE=ON" }
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        prefab = true
    }
    testBuildType = "sonyTest"
    sourceSets.getByName("sonyRelease").res.directories.add("src/sonyTest/res")
    lint { baseline = file("lint-baseline.xml") }
    if (nativeApk != null) sourceSets.getByName("main").jniLibs.directories.add(prebuiltJni.get().asFile.absolutePath)
}

if (nativeApk != null) {
    val extractUpstreamNative = tasks.register<Sync>("extractUpstreamNative") {
        inputs.file(nativeApk)
        doFirst {
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(nativeApk.readBytes()).joinToString("") { "%02x".format(it) }
            check(hash == "b8c7c595ed93f91562feedce92c27800f08f9c84df424f6ac2045e82324cf2a4") {
                "airplayNativeApk must be the official, unmodified v0.0.36 app-release.apk"
            }
        }
        from(zipTree(nativeApk)) {
            include("lib/*/libairplay_native.so", "lib/*/libcrypto.so", "lib/*/libc++_shared.so", "lib/*/liboboe.so")
            eachFile { path = path.removePrefix("lib/") }
            includeEmptyDirs = false
        }
        into(prebuiltJni)
    }
    tasks.named("preBuild").configure { dependsOn(extractUpstreamNative) }
}

tasks.register("applyUxplayPatches") {
    doLast {
        fun git(vararg args: String): String {
            val proc = ProcessBuilder("git", "-C", "$projectDir/src/main/cpp/third_party/UxPlay", *args)
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            check(proc.waitFor() == 0) { "git ${args.joinToString(" ")} failed:\n$out" }
            return out
        }
        val patches = file("src/main/cpp/patches/UxPlay").listFiles { f -> f.extension == "patch" }!!.sorted()
        val touched = patches.flatMap { git("apply", "--numstat", it.path).trim().lines() }
            .map { it.substringAfterLast("\t") }.distinct()
        git("checkout", "--", *touched.toTypedArray())
        patches.forEach { git("apply", "--unidiff-zero", it.path) }
    }
}

tasks.configureEach {
    if (name.startsWith("configureCMake")) dependsOn("applyUxplayPatches")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.datastore.prefs)
    implementation(libs.androidx.media)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui.compose.material3)
    implementation(libs.media3.transformer)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.oboe)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
}
