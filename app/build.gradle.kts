plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.cloneforge.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.cloneforge.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures { compose = true }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
            excludes += "META-INF/*.SF"
            excludes += "META-INF/*.DSA"
            excludes += "META-INF/*.RSA"
            excludes += "META-INF/*.EC"
            excludes += "META-INF/versions/**"
            excludes += "META-INF/*.kotlin_module"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.android.tools.build:apksig:8.7.3")
    implementation("org.smali:dexlib2:2.5.2")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    dependsOn(":fixture:assembleDebug")
    systemProperty(
        "cloneforge.fixtureApk",
        project(":fixture").layout.buildDirectory
            .file("outputs/apk/debug/fixture-debug.apk")
            .get()
            .asFile
            .absolutePath
    )
}
