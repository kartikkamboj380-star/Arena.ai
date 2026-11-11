import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.kartikkamboj.arena"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kartikkamboj.arena"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // No signing config on purpose: release artefacts are built unsigned in CI, and a
            // keystore must never end up in a repository. docs/BUILD.md covers providing one
            // through GitHub Actions secrets.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // AppLog delegates to android.util.Log; returning defaults keeps JVM tests honest about
            // logic instead of failing on a stubbed framework call.
            isReturnDefaultValues = true
            isIncludeAndroidResources = false
            all {
                it.testLogging {
                    events("passed", "skipped", "failed")
                }
            }
        }
    }

    lint {
        // Findings are reported in CI but must not turn the build red on style-only issues.
        abortOnError = false
        warningsAsErrors = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-opt-in=kotlin.RequiresOptIn", "-Xjsr305=strict")
    }
}

// The app is a WebView shell: the platform provides the browser, the input method, the downloads,
// the file picker and the notifications. Everything below is either Compose chrome or a supported
// bridge to one of those. No HTTP client, no JSON parser, no image loader, no persistence layer.
dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.browser)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    // The instrumentation runner named in defaultConfig lives in androidx.test:runner; without it
    // the APK assembles but connectedDebugAndroidTest cannot start.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui)
}
