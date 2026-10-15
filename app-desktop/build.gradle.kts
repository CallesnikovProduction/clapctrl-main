import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins { kotlin("jvm"); id("org.jetbrains.compose"); id("org.jetbrains.kotlin.plugin.compose") }
kotlin { jvmToolchain(25) }
dependencies {
    implementation(project(":core"))
    implementation(project(":platform"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material:material:1.10.3")
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4:1.10.3")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.12.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.slf4j:slf4j-simple:2.0.17")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.withType<Test>().configureEach { jvmArgs("--enable-native-access=ALL-UNNAMED") }
compose.desktop {
    application {
        mainClass = "dev.clapctrl.app.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "ClapCtrl"
            packageVersion = "0.1.0"
            description = "Local acoustic desktop automation"
            vendor = "ClapCtrl contributors"
            copyright = "Copyright 2026 ClapCtrl contributors"
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.desktop", "java.logging", "java.management", "jdk.unsupported")
            windows {
                iconFile.set(rootProject.file("packaging/windows/clapctrl.ico"))
                menuGroup = "ClapCtrl"
                shortcut = true
                perUserInstall = true
                upgradeUuid = "9fb7d639-ae70-459c-9673-e56d22cff246"
            }
            linux { menuGroup = "Utility"; appCategory = "Utility"; iconFile.set(rootProject.file("packaging/linux/clapctrl.png")) }
        }
    }
}
