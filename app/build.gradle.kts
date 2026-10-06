plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("jacoco")
}

android {
    namespace = "cash.nonfungible.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "cash.nonfungible"
        // java.util.Base64 in the nostr code needs API 26.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            enableUnitTestCoverage = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.palette:palette:1.0.0")

    // Nostr: secp256k1 and ChaCha20 (BouncyCastle), relay sockets (OkHttp), event JSON (Gson)
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // The request: CBOR, drawn as a QR code
    implementation("com.upokecenter:cbor:4.5.2")
    implementation("com.google.zxing:core:3.5.3")

    // Reading a door's code with the camera, for a guest: CameraX and ML Kit
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("androidx.test:core:1.5.0")
}

tasks.withType<Test>().configureEach {
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(21)
    }

    configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(true)
    }

    val buildDir = layout.buildDirectory.get().asFile

    sourceDirectories.setFrom(files("${project.projectDir}/src/main/java"))
    classDirectories.setFrom(fileTree("$buildDir/tmp/kotlin-classes/debug") {
        exclude("**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*")
    })
    executionData.setFrom(fileTree(buildDir) {
        include(
            "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
            "jacoco/testDebugUnitTest.exec"
        )
    })
}
