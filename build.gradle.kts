plugins {
    alias(libs.plugins.aboutlibraries) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.screenshot) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.play.publisher) apply false
    alias(libs.plugins.room3) apply false
}

spotless {
    kotlin {
        target("app/src/**/*.kt")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
}

// Spotless creates root lifecycle tasks; keep them connected to the Android module.
for (task in listOf("assemble", "build", "check", "clean")) {
    tasks.named(task) {
        dependsOn(":app:$task")
    }
}
