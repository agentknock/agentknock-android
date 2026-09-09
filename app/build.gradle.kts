import com.github.triplet.gradle.androidpublisher.ReleaseStatus
import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import com.google.gms.googleservices.GoogleServicesTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.work.DisableCachingByDefault

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.screenshot)
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.play.publisher)
    alias(libs.plugins.room3)
}

val agentknockVersionCode = 47
val agentknockVersionName = "0.2.0"
val playCredentialsFile = providers.environmentVariable("AGENTKNOCK_PLAY_CREDENTIALS_FILE")
val sourceRevision =
    providers.environmentVariable("AGENTKNOCK_SOURCE_REVISION").orElse("unverified")

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

    flavorDimensions += "distribution"
    productFlavors {
        create("play") { dimension = "distribution" }
        create("foss") { dimension = "distribution" }
    }

    playConfigs {
        register("playRelease") { enabled.set(true) }
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
    enabled.set(false)
    defaultToAppBundles.set(true)
    releaseName.set("$agentknockVersionName-internal.$agentknockVersionCode")
    releaseStatus.set(ReleaseStatus.COMPLETED)
    track.set("internal")

    if (playCredentialsFile.isPresent) {
        serviceAccountCredentials.set(file(playCredentialsFile.get()))
    }
}

// Wire Firebase configuration only into Play variants. Applying the plugin globally
// would register a Google Services task and generated resources for FOSS as well.
androidComponents {
    onVariants(selector().withFlavor("distribution" to "play")) { variant ->
        val googleServices =
            tasks.register<GoogleServicesTask>(
                "process${variant.name.replaceFirstChar { it.uppercase() }}GoogleServices"
            ) {
                googleServicesJsonFiles.set(listOf(file("src/play/google-services.json")))
                applicationId.set(variant.applicationId)
                missingGoogleServicesStrategy.set(MissingGoogleServicesStrategy.ERROR)
                gmpAppId.set(layout.buildDirectory.file("gmpAppId/${variant.name}.txt"))
                outputDirectory.set(
                    layout.buildDirectory.dir("generated/google-services/${variant.name}")
                )
            }
        variant.sources.res?.addGeneratedSourceDirectory(
            googleServices,
            GoogleServicesTask::outputDirectory,
        )
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
    "playImplementation"(platform(libs.firebase.bom))
    "playImplementation"(libs.firebase.messaging)
    "playImplementation"(libs.google.play.billing)
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

val requirePlayCredentials =
    tasks.register("requirePlayCredentials") {
        doLast {
            check(playCredentialsFile.isPresent) {
                "Play publishing requires AGENTKNOCK_PLAY_CREDENTIALS_FILE"
            }
        }
    }

tasks
    .matching { it.name == "publishPlayReleaseBundle" }
    .configureEach {
        dependsOn(requirePlayCredentials)
    }

@DisableCachingByDefault(because = "Checks resolved coordinates without producing artifacts")
abstract class VerifyFossDependencies : DefaultTask() {
    @get:Input abstract val dependencyCoordinates: MapProperty<String, List<String>>

    @TaskAction
    fun verify() {
        val forbiddenGroups =
            setOf(
                "com.google.firebase",
                "com.google.android.gms",
                "com.android.billingclient",
            )
        val violations =
            dependencyCoordinates.get().flatMap { (configuration, coordinates) ->
                coordinates
                    .filter { it.substringBefore(':') in forbiddenGroups }
                    .map { "$configuration: $it" }
            }
        check(violations.isEmpty()) {
            "FOSS builds must not include Firebase, Google Play Services, or Play Billing:\n" +
                violations.sorted().joinToString("\n")
        }
    }
}

val verifyFossDependencies =
    tasks.register<VerifyFossDependencies>("verifyFossDependencies") {
        group = "verification"
        description = "Rejects Google SDK dependencies in the FOSS debug and release runtimes."
        for (buildType in listOf("Debug", "Release")) {
            val configuration = "foss${buildType}RuntimeClasspath"
            dependencyCoordinates.put(
                configuration,
                configurations
                    .named(configuration)
                    .flatMap { it.incoming.artifacts.resolvedArtifacts }
                    .map { artifacts ->
                        artifacts
                            .mapNotNull { artifact ->
                                (artifact.id.componentIdentifier as? ModuleComponentIdentifier)
                                    ?.let {
                                        "${it.group}:${it.module}:${it.version}"
                                    }
                            }
                            .distinct()
                            .sorted()
                    },
            )
        }
    }

tasks.named("check") {
    dependsOn(":spotlessCheck")
    dependsOn(verifyFossDependencies)
    for (flavor in listOf("Foss", "Play")) {
        dependsOn(
            "assemble${flavor}Debug",
            "test${flavor}DebugUnitTest",
            "lint${flavor}Debug",
            "compile${flavor}DebugScreenshotTestSources",
        )
    }
}
