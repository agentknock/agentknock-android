plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(localGroovy())
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
