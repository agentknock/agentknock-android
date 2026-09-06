import com.github.triplet.gradle.androidpublisher.ReleaseStatus

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.screenshot)
    alias(libs.plugins.google.services)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.play.publisher)
    alias(libs.plugins.room3)
}

val agentknockVersionCode = 44
val agentknockVersionName = "0.2.0"
val playCredentialsFile = providers.environmentVariable("AGENTKNOCK_PLAY_CREDENTIALS_FILE")
val sourceRevision = providers.environmentVariable("AGENTKNOCK_SOURCE_REVISION")
    .orElse("unverified")

android {
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
    namespace = "dev.agentknock"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.agentknock"
        minSdk = 26
        targetSdk = 37
        versionCode = agentknockVersionCode
        versionName = agentknockVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SOURCE_REVISION", "\"${sourceRevision.get()}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

play {
    defaultToAppBundles.set(true)
    releaseName.set("$agentknockVersionName-internal.$agentknockVersionCode")
    releaseStatus.set(ReleaseStatus.COMPLETED)
    track.set("internal")

    if (playCredentialsFile.isPresent) {
        serviceAccountCredentials.set(file(playCredentialsFile.get()))
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.work.runtime)
    implementation(libs.bouncycastle.provider)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.google.play.billing)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.compose.ui.test.manifest)

    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)

    ksp(libs.androidx.room3.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver3)

    androidTestImplementation(libs.androidx.room3.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

val requirePlayCredentials = tasks.register("requirePlayCredentials") {
    doLast {
        check(playCredentialsFile.isPresent) {
            "Play publishing requires AGENTKNOCK_PLAY_CREDENTIALS_FILE"
        }
    }
}

tasks.matching { it.name == "publishReleaseBundle" }.configureEach {
    dependsOn(requirePlayCredentials)
}
