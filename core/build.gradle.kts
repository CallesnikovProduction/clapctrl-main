plugins { kotlin("jvm"); kotlin("plugin.serialization"); `java-library` }
kotlin { jvmToolchain(25) }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.slf4j:slf4j-api:2.0.17")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
tasks.register<JavaExec>("evaluateAudio") {
    workingDir(rootProject.projectDir)
    group = "verification"
    description = "Evaluate held-out WAV recordings: --args='manifest.json [profile.json] [report.json]'"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.clapctrl.core.calibration.DatasetEvaluatorKt")
}
