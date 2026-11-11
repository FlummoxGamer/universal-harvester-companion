plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "app.harvester.companion"
    compileSdk = 36

    defaultConfig {
    applicationId = "app.harvester.companion"
    minSdk = 29
    targetSdk = 36
    versionCode = 159
    versionName = "2.4.89"

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.generateKotlin", "true")
    }

    buildConfigField("String", "AMO_BASE_URL", "\"https://addons.mozilla.org\"")
    buildConfigField("String", "AMO_COLLECTION_NAME", "\"Extensions-for-Android\"")
    buildConfigField("String", "AMO_COLLECTION_USER", "\"mozilla\"")
    buildConfigField("String", "AMO_SERVER_URL", "\"https://services.addons.mozilla.org\"")
}

    flavorDimensions += "version"
    productFlavors {
        create("stable") {
            dimension = "version"
            applicationIdSuffix = ""
            versionNameSuffix = ""
            resValue("string", "app_name", "UH")
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
        resValues = true
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
        jniLibs {
            pickFirsts += "**/libxul.so"
        }
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
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
    
    implementation("com.iqiyi.xcrash:xcrash-android-lib:3.1.0")
    
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("org.mozilla.components:browser-engine-gecko:154.0.1")
    implementation("org.mozilla.components:browser-state:154.0.1")
    implementation("org.mozilla.components:concept-engine:154.0.1")
    implementation("org.mozilla.components:feature-session:154.0.1")
    implementation("org.mozilla.components:feature-downloads:154.0.1")
    implementation("org.mozilla.components:feature-prompts:154.0.1")
    implementation("org.mozilla.components:feature-tabs:154.0.1")
    implementation("org.mozilla.components:support-base:154.0.1")
    implementation("org.mozilla.components:support-utils:154.0.1")
    implementation("org.mozilla.components:support-ktx:154.0.1")
    implementation("org.mozilla.components:support-locale:154.0.1")
    implementation("org.mozilla.components:support-images:154.0.1")
    implementation("org.mozilla.components:support-webextensions:154.0.1")
    implementation("org.mozilla.components:support-remotesettings:154.0.1") {
        exclude(group = "org.mozilla.telemetry", module = "glean-native")
    }
    implementation("org.mozilla.components:concept-toolbar:154.0.1")
    implementation("org.mozilla.components:concept-awesomebar:154.0.1")
    implementation("org.mozilla.components:feature-toolbar:154.0.1")
    implementation("org.mozilla.components:feature-search:154.0.1")
    implementation("org.mozilla.components:feature-awesomebar:154.0.1")
    implementation("org.mozilla.components:feature-contextmenu:154.0.1")
    implementation("org.mozilla.components:feature-app-links:154.0.1")
    implementation("org.mozilla.components:feature-sitepermissions:154.0.1")
    implementation("org.mozilla.components:feature-privatemode:154.0.1")
    implementation("org.mozilla.components:feature-addons:154.0.1")
    implementation("org.mozilla.components:browser-menu2:154.0.1")
    implementation("org.mozilla.components:browser-thumbnails:154.0.1")
    implementation("org.mozilla.components:browser-icons:154.0.1")
    implementation("org.mozilla.components:browser-session-storage:154.0.1")
    implementation("org.mozilla.components:compose-base:154.0.1")
    implementation("org.mozilla.components:compose-browser-toolbar:154.0.1")
    implementation("org.mozilla.components:ui-icons:154.0.1")
    implementation("org.mozilla.components:ui-widgets:154.0.1")
    implementation("org.mozilla.components:service-digitalassetlinks:154.0.1")

    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    implementation("androidx.room:room-paging:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.paging:paging-runtime-ktx:3.2.1")
    implementation("androidx.fragment:fragment-compose:1.8.2")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
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
configurations.all {
    exclude(group = "org.mozilla.telemetry", module = "glean-native")
}
