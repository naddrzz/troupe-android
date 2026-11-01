import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Version: versionName is the `v*` tag on HEAD without its `v` (`v1.2.3` -> `1.2.3`),
// `0.0.0-dev` when HEAD is not tagged. versionCode is the commit count, which grows
// with every commit on the release line (see "Releasing" in the README).
fun git(vararg args: String): String? {
    val result = providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }
    return if (result.result.get().exitValue == 0) result.standardOutput.asText.get().trim().ifEmpty { null } else null
}

val troupeVersionName = git("describe", "--tags", "--exact-match", "--match", "v*")?.removePrefix("v") ?: "0.0.0-dev"
val troupeVersionCode = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1

// Release signing: environment variables first (CI), then a git-ignored keystore.properties
// at the repo root. Without either, the release APK is built unsigned.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun signingValue(env: String, property: String): String? =
    System.getenv(env)?.ifBlank { null } ?: keystoreProperties.getProperty(property)?.ifBlank { null }

val releaseKeystoreFile = signingValue("TROUPE_KEYSTORE_FILE", "storeFile")
val releaseKeystorePassword = signingValue("TROUPE_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("TROUPE_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("TROUPE_KEY_PASSWORD", "keyPassword")
val hasReleaseSigning = listOf(releaseKeystoreFile, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }

android {
    namespace = "dev.troupe.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.troupe.android"
        minSdk = 26
        targetSdk = 36
        versionCode = troupeVersionCode
        versionName = troupeVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseKeystoreFile!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(21)
}

// The integration test talks to a real Troupe Server (see integration/), so the
// regular unit test run leaves it out and `integrationTest` runs only it.
tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest" || name == "testReleaseUnitTest") {
        exclude("**/integration/**")
    }
}

afterEvaluate {
    val unitTest = tasks.named<Test>("testDebugUnitTest").get()
    tasks.register<Test>("integrationTest") {
        description = "Runs the integration test against a real Troupe Server (scripts/test-server.ps1 up)."
        group = "verification"
        testClassesDirs = unitTest.testClassesDirs
        classpath = unitTest.classpath
        include("**/integration/**")
        outputs.upToDateWhen { false }
        testLogging { events("passed", "skipped", "failed") }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.tink.android)
    implementation(libs.androidx.webkit)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
