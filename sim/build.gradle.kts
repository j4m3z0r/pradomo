import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":shared")) // reuses the real maneuver/control code (jvm variant)
}

// Match :shared (JVM 17 bytecode) without requiring a JDK 17 toolchain to be installed —
// compiles on whatever JDK runs Gradle, same as the other modules.
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    // ./gradlew :sim:run --args="assess"   (or "tune")
    mainClass.set("com.pradomo.sim.MainKt")
}

// Run from the repo root so the program's "sim/out" output lands at <root>/sim/out
// (which .gitignore covers), not <root>/sim/sim/out.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
