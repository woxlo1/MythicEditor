import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
}

group = "dev.mythiceditor"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// ---------------------------------------------------------------------------
// This is the buildable plugin module: platform/command glue (Bukkit/Paper +
// Adventure API, MythicMobs access) plus the bundled web/ front-end. All of
// the actual editor logic lives in the sibling :core module (see core/build.gradle.kts),
// which has no Bukkit dependency and can be built/tested standalone.
//
// Normal build : Paper API comes from the PaperMC repository.
// Offline build: ./gradlew build -PofflineLibs=/path/to/dir   (flatDir with jars)
//                Used in sandboxes where repo.papermc.io / Maven Central are unreachable.
//                Note: paper-api itself has no legal offline substitute, so an offline build
//                can compile/test :core but cannot compile this module's Bukkit-dependent code.
// MythicMobs is NOT a compile dependency: it is accessed by reflection in
// dev.mythiceditor.platform.ReflectiveMythicAdapter so the plugin builds and loads
// across MythicMobs 5.x API revisions.
// ---------------------------------------------------------------------------
val offlineLibs: String? = providers.gradleProperty("offlineLibs").orNull

repositories {
    if (offlineLibs != null) {
        flatDir { dirs(offlineLibs) }
    } else {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    implementation(project(":core"))
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing"))
}

// ---------------------------------------------------------------------------
// Web front-end (web/ -> Vite build -> jar resources under /web)
// Skip with -PskipWeb (Java-only iterations).
// ---------------------------------------------------------------------------
val skipWeb = providers.gradleProperty("skipWeb").isPresent
val webDir = layout.projectDirectory.dir("web")
val isWindows = System.getProperty("os.name").lowercase().contains("win")
val npm = if (isWindows) "npm.cmd" else "npm"

val webInstall = tasks.register<Exec>("webInstall") {
    group = "web"
    workingDir = webDir.asFile
    commandLine(npm, "install", "--no-audit", "--no-fund")
    inputs.file(webDir.file("package.json"))
    outputs.dir(webDir.dir("node_modules"))
    onlyIf { !skipWeb }
}

val webBuild = tasks.register<Exec>("webBuild") {
    group = "web"
    dependsOn(webInstall)
    workingDir = webDir.asFile
    commandLine(npm, "run", "build")
    inputs.dir(webDir.dir("src"))
    inputs.files(webDir.file("index.html"), webDir.file("package.json"), webDir.file("vite.config.ts"))
    outputs.dir(webDir.dir("dist"))
    onlyIf { !skipWeb }
}

tasks.processResources {
    dependsOn(webBuild)
    from(webDir.dir("dist")) { into("web") }
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.jar {
    archiveBaseName.set("MythicEditor")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(":core:classes")
    // :core has no runtime deps of its own (Gson/SnakeYAML are compileOnly there, provided by Paper
    // at runtime - see core/build.gradle.kts), so merging just its class files is enough; no shading
    // of third-party jars is needed or performed.
    from(project(":core").sourceSets.main.get().output)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
