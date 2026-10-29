// Pure Kotlin/JVM: domain logic and Toyota client, no Android dependencies.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// Manual check against a real MyToyota account (docs/HANDOFF.md). Never run in CI:
// it needs TOYOTA_USERNAME / TOYOTA_PASSWORD from the owner's shell.
sourceSets {
    create("liveCheck") {
        compileClasspath += sourceSets.main.get().output + configurations.runtimeClasspath.get()
        runtimeClasspath += output + compileClasspath
    }
}

tasks.register<JavaExec>("liveCheck") {
    description = "Logs in to a real MyToyota account and prints a read-only summary."
    group = "verification"
    classpath = sourceSets["liveCheck"].runtimeClasspath
    mainClass.set("pl.lk.toyotacompanion.core.livecheck.LiveCheckKt")
}
