plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.mok.buylist"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mok.buylist"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Подпись отладочным ключом — чтобы оптимизированную сборку можно было поставить на телефон и проверить плавность.
            // Для публикации заменить настоящим ключом.
            signingConfig = signingConfigs.getByName("debug")
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

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
