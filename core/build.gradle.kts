// ---------------------------------------------------------------------------
// core: plain-Java module with zero Bukkit/Paper dependency. All of the
// editor's logic (YAML parsing, storage, the HTTP API, the catalog, the
// relation graph) lives here so it can be unit tested in any environment,
// including one with no MythicMobs/Paper server available. The root project
// depends on this module and its Bukkit-dependent platform/command classes
// only wire it up to the server.
// ---------------------------------------------------------------------------
plugins {
    java
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val offlineLibs: String? = providers.gradleProperty("offlineLibs").orNull

repositories {
    if (offlineLibs != null) {
        flatDir { dirs(offlineLibs) }
    } else {
        mavenCentral()
    }
}

dependencies {
    // compileOnly: Paper (and therefore the final plugin jar's runtime classpath) already ships
    // both Gson and SnakeYAML, so they are never bundled into the plugin jar - see the root
    // build.gradle.kts jar task, which merges only this module's own compiled classes.
    if (offlineLibs != null) {
        compileOnly(":gson:2.10.1")
        compileOnly(":snakeyaml:1.33")
        testImplementation(":gson:2.10.1")
        testImplementation(":snakeyaml:1.33")
        testImplementation(":junit-jupiter-api:5.10.1")
        testImplementation(":junit-jupiter-params:5.10.1")
        testRuntimeOnly(":junit-jupiter-engine:5.10.1")
        testRuntimeOnly(":junit-platform-engine:1.9.1")
        testRuntimeOnly(":junit-platform-commons:1.9.1")
        testRuntimeOnly(":junit-platform-launcher:1.9.1")
        testRuntimeOnly(":opentest4j:1.2.0")
        testRuntimeOnly(":apiguardian-api:1.1.2")
    } else {
        compileOnly("com.google.code.gson:gson:2.11.0")
        compileOnly("org.yaml:snakeyaml:2.2")
        testImplementation("com.google.code.gson:gson:2.11.0")
        testImplementation("org.yaml:snakeyaml:2.2")
        testImplementation(platform("org.junit:junit-bom:5.10.2"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing"))
}

// The relay Worker the plugin deploys for you: relay-worker/src/worker.js is the single source of truth.
tasks.processResources {
    from("../relay-worker/src") {
        include("worker.js")
        into("relay")
    }
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
