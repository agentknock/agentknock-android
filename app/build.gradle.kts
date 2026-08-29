import com.github.triplet.gradle.androidpublisher.ReleaseStatus

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.google.services)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.play.publisher)
    alias(libs.plugins.room3)
}

val agentknockVersionCode = 29
val agentknockVersionName = "0.1.0"
val uploadStoreFile = providers.environmentVariable("AGENTKNOCK_UPLOAD_STORE_FILE")
val uploadStorePassword = providers.environmentVariable("AGENTKNOCK_UPLOAD_STORE_PASSWORD")
val uploadKeyAlias = providers.environmentVariable("AGENTKNOCK_UPLOAD_KEY_ALIAS")
val uploadKeyPassword = providers.environmentVariable("AGENTKNOCK_UPLOAD_KEY_PASSWORD")
val uploadSigningValues = listOf(
    uploadStoreFile,
    uploadStorePassword,
    uploadKeyAlias,
    uploadKeyPassword,
)
val uploadSigningConfigured = uploadSigningValues.all { it.isPresent }
val playCredentialsFile = providers.environmentVariable("AGENTKNOCK_PLAY_CREDENTIALS_FILE")
val sourceRevision = providers.environmentVariable("AGENTKNOCK_SOURCE_REVISION")
    .orElse("unverified")

require(uploadSigningValues.none { it.isPresent } || uploadSigningConfigured) {
    "Set all Agentknock upload-signing environment variables or none of them"
}

android {
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

    signingConfigs {
        if (uploadSigningConfigured) {
            create("upload") {
                storeFile = file(uploadStoreFile.get())
                storePassword = uploadStorePassword.get()
                keyAlias = uploadKeyAlias.get()
                keyPassword = uploadKeyPassword.get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("upload")
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
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.work.runtime)
    implementation(libs.bouncycastle.provider)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    ksp(libs.androidx.room3.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver3)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room3.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

val requireReleaseSigning = tasks.register("requireReleaseSigning") {
    doLast {
        check(uploadSigningConfigured) {
            "Release builds require the Agentknock upload-signing environment variables"
        }
    }
}

tasks.matching { it.name == "bundleRelease" || it.name == "packageRelease" }.configureEach {
    dependsOn(requireReleaseSigning)
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
