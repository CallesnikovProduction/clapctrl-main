plugins { kotlin("jvm"); `java-library` }
kotlin { jvmToolchain(25) }
dependencies {
    api(project(":core"))
    implementation("org.slf4j:slf4j-api:2.0.17")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
tasks.register<JavaExec>("audioDiagnostics") {
    group = "verification"
    description = "List real microphone devices; capture only with explicit --capture-ms option"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.clapctrl.platform.AudioDiagnosticsKt")
}
