plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

// Release version from the tag (`-PappVersion=0.2.0`, docs/SPEC.md §37, ADR-016). versionCode has the formula of
// core AppVersion.codeOf: the app compares it with the release tag on GitHub.
val appVersion: String = providers.gradleProperty("appVersion").getOrElse("0.1.0")
val appVersionCode: Int = Regex("""(\d{1,3})\.(\d{1,2})\.(\d{1,2})""").matchEntire(appVersion)
    ?.destructured?.let { (major, minor, patch) -> major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt() }
    ?: error("appVersion must be MAJOR.MINOR.PATCH with MINOR and PATCH up to 99, got '$appVersion'")

// Release signing only from the environment (GitHub secrets in .github/workflows/release.yml); never in the repo.
val releaseKeystore: String? = System.getenv("RELEASE_KEYSTORE_FILE")

android {
    namespace = "pl.lk.toyotacompanion"
    compileSdk = 37

    defaultConfig {
        applicationId = "pl.lk.toyotacompanion"
        minSdk = 29
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Without the key a release build stays unsigned and cannot be installed; CI fails on it before publishing.
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
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
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric on JDK 17+ reaches into java.base internals (FileDescriptor via SharedSecrets).
                it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
            }
        }
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.room3.runtime)
    implementation(libs.sqlite.framework)
    implementation(libs.work.runtime)
    // Android Auto: templated screen of the car app (docs/SPEC.md §35, ADR-014).
    implementation(libs.car.app)
    implementation(libs.coroutines.core)
    ksp(libs.room3.compiler)
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)

    // Unit tests on the JVM: Robolectric (JUnit 4 runner), Room/WorkManager test helpers.
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.room3.testing)
    testImplementation(libs.work.testing)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.kotlin.test)
    testImplementation(testFixtures(project(":core")))
}
