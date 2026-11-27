plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/*
 * Release credentials come from CI environment variables or the developer's
 * private ~/.gradle/gradle.properties. Never use the generated debug key.
 */
fun signingValue(name: String) =
    providers.environmentVariable(name).orElse(providers.gradleProperty(name))

val releaseStoreFile = signingValue("BUYLIST_STORE_FILE")
val releaseStorePassword = signingValue("BUYLIST_STORE_PASSWORD")
val releaseKeyAlias = signingValue("BUYLIST_KEY_ALIAS")
val releaseKeyPassword = signingValue("BUYLIST_KEY_PASSWORD")

val appVersion = providers.gradleProperty("buylist.version").get()
val appVersionCode = providers.environmentVariable("BUYLIST_VERSION_CODE").orElse("3").map { it.toInt() }
val appVersionName = providers.environmentVariable("BUYLIST_VERSION_NAME").orElse("$appVersion-beta.local")

android {
    namespace = "com.mok.buylist"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mok.buylist"
        minSdk = 29
        targetSdk = 37
        versionCode = appVersionCode.get()
        versionName = appVersionName.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = releaseStoreFile.orNull?.let { rootProject.file(it) }
            storePassword = releaseStorePassword.orNull
            keyAlias = releaseKeyAlias.orNull
            keyPassword = releaseKeyPassword.orNull
        }
    }

    buildTypes {
        release {
            // Один постоянный ключ для всех обновлений приложения.
            signingConfig = signingConfigs.getByName("release")
            // Без packageScope: с ним R8 переименовывал классы Kotlin так, что приложение падало при запуске
            // (IllegalAccessError: kotlin.collections.h ... inaccessible).
            optimization {
                enable = true
                keepRules {
                    files.add(project.file("proguard-rules.pro"))
                }
            }
        }
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        // Интерфейс только русский; переводы системных библиотек на десятки других языков в приложение не берём.
        localeFilters += listOf("ru", "en")
    }
    packaging {
        resources {
            // Служебные файлы библиотек, которые во время работы не читаются.
            excludes += listOf(
                "/META-INF/androidx/**",
                "/META-INF/*.version",
                "/META-INF/**/LICENSE*",
                "/META-INF/AL2.0",
                "/META-INF/LGPL2.1",
                "/kotlin/**",
                "/kotlin-tooling-metadata.json",
                "/DebugProbesKt.bin",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.glance.appwidget)

    // Синхронизация по QR: камера (CameraX) читает кадры, ZXing кодирует и распознаёт QR.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.play.services.nearby)
    // Pure Java SSH transport diagnostic; no native agent or export of Keystore keys.
    implementation("com.github.mwiede:jsch:2.28.8")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
