import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val releaseSigningPropertiesFile = rootProject.file("keystore.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use { input -> load(input) }
    }
}

android {
    namespace = "xyz.sakulik.d20.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "xyz.sakulik.d20.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables {
            useSupportLibrary = true
        }

        testInstrumentationRunner = "xyz.sakulik.d20.app.testing.D20TestRunner"

    }

    signingConfigs {
        if (releaseSigningPropertiesFile.isFile) {
            create("release") {
                storeFile = rootProject.file(
                    releaseSigningProperties.getProperty("storeFile")
                        ?: error("keystore.properties 缺少 storeFile")
                )
                storePassword = releaseSigningProperties.getProperty("storePassword")
                    ?: error("keystore.properties 缺少 storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                    ?: error("keystore.properties 缺少 keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
                    ?: error("keystore.properties 缺少 keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        create("instrumented") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".instrumented"
            versionNameSuffix = "-instrumented"
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    testBuildType = "instrumented"
    sourceSets {
        getByName("instrumented") {
            java.srcDir("src/debug/java")
            manifest.srcFile("src/debug/AndroidManifest.xml")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    
    // Compose
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    
    // Room
    val room_version = "2.6.1"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    ksp("androidx.room:room-compiler:$room_version")

    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Markdown
    implementation("com.atlassian.commonmark:commonmark:0.13.0")
    implementation("com.atlassian.commonmark:commonmark-ext-gfm-strikethrough:0.13.0")
    implementation("com.atlassian.commonmark:commonmark-ext-gfm-tables:0.13.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.room:room-testing:$room_version")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    add("instrumentedImplementation", "androidx.compose.ui:ui-tooling")
    add("instrumentedImplementation", "androidx.compose.ui:ui-test-manifest")
}
