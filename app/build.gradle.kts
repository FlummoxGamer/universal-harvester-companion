plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.harvester.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.harvester.companion"
        minSdk = 29
        targetSdk = 36
        versionCode = 131
        versionName = "2.4.61"
    }

    flavorDimensions += "version"
    productFlavors {
        create("stable") {
            dimension = "version"
            applicationIdSuffix = ""
            versionNameSuffix = ""
            resValue("string", "app_name", "Universal Harvester")
        }
        create("dev") {
            dimension = "version"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "UH Dev")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    androidResources {
        noCompress += "ja"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.version"
            )
        }
    }

    signingConfigs {
        create("shared") {
            val ks = file("../uh-debug.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "uhdebug123"
                keyAlias = "uhdebug"
                keyPassword = "uhdebug123"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            signingConfig = signingConfigs.getByName("shared")
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "NONE"
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.squareup.leakcanary:leakcanary-android:2.14")

    val acVersion = "154.0.1"
    implementation("org.mozilla.components:browser-engine-gecko:$acVersion")
    implementation("org.mozilla.components:browser-state:$acVersion")
    implementation("org.mozilla.components:browser-session-storage:$acVersion")
    implementation("org.mozilla.components:browser-icons:$acVersion")
    implementation("org.mozilla.components:browser-toolbar:$acVersion")
    implementation("org.mozilla.components:compose-browser-toolbar:$acVersion")
    implementation("org.mozilla.components:concept-engine:$acVersion")
    implementation("org.mozilla.components:concept-storage:$acVersion")
    implementation("org.mozilla.components:feature-session:$acVersion")
    implementation("org.mozilla.components:feature-toolbar:$acVersion")
    implementation("org.mozilla.components:feature-tabs:$acVersion")
    implementation("org.mozilla.components:feature-prompts:$acVersion")
    implementation("org.mozilla.components:feature-downloads:$acVersion")
    implementation("org.mozilla.components:feature-findinpage:$acVersion")
    implementation("org.mozilla.components:feature-sitepermissions:$acVersion")
    
    implementation("org.mozilla.components:feature-search:$acVersion") {
        exclude(group = "org.mozilla.telemetry", module = "glean-native")
    }
    
    implementation("org.mozilla.components:lib-state:$acVersion")
    implementation("org.mozilla.components:support-base:$acVersion")
    implementation("org.mozilla.components:support-ktx:$acVersion")
    implementation("org.mozilla.components:support-utils:$acVersion")
    implementation("org.mozilla.components:ui-icons:$acVersion")
    implementation("org.mozilla.components:ui-widgets:$acVersion")

    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.0")

    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("io.coil-kt:coil-gif:2.6.0")
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("io.coil-kt:coil-video:2.6.0")
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9")
}

tasks.configureEach {
    if (name.startsWith("check") && name.endsWith("AarMetadata")) {
        enabled = false
    }
}
tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }.configureEach {
    enabled = false
}
