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
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
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
    debugImplementation("androidx.compose.ui:ui-tooling")
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