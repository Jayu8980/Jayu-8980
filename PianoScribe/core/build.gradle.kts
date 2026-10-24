// Pure Kotlin/JVM engine: DSP, transcription post-processing, music theory, MIDI/MusicXML writers, synth.
// No Android dependencies, so everything here is unit-tested on the JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
    // Only for the model accuracy evaluation (runs when CI provides the converted models).
    testImplementation(libs.onnxruntime.jvm)
}

tasks.test {
    maxHeapSize = "3g"
    testLogging { events("failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// Regenerates the bundled piano SoundFont: ./gradlew :core:generateSoundFont
tasks.register<JavaExec>("generateSoundFont") {
    group = "pianoscribe"
    description = "Synthesises app/src/main/assets/soundfonts/pianoscribe_piano.sf2"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.pianoscribe.core.synth.PianoSampleGeneratorKt")
    args(rootProject.file("app/src/main/assets/soundfonts/pianoscribe_piano.sf2").absolutePath)
}
