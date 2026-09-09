plugins {
    alias(libs.plugins.shadow)
}

group = "dev.bcrick"
// Pinned equal to dev.bcrick.bitwigpal.BitwigPalVersion.VERSION by VersionConsistencyTest, which
// reads this line as text -- a JUnit test cannot see a Gradle value any other way. D-21-H: one
// version string. Move this and the constant together, or the test fails naming both.
version = "0.2.0"

val bitwigExtensionsDir: String by project

// --- Main (extension) source set ---

dependencies {
    implementation(libs.java.websocket)
    implementation(libs.gson)

    testImplementation(libs.bitwig.api)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

val generateOpenApi by tasks.registering(Exec::class) {
    description = "Generate OpenAPI spec from claude-tools.json"
    commandLine("node", "scripts/generate-openapi.mjs")
    inputs.file("tools/claude-tools.json")
    inputs.file("scripts/generate-openapi.mjs")
    outputs.file("docs/openapi.json")
}

val copyDocsToResources by tasks.registering(Copy::class) {
    dependsOn(generateOpenApi)
    from("docs/api.html", "docs/openapi.json")
    into(layout.buildDirectory.dir("resources/main/docs"))
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(copyDocsToResources)
}

tasks.shadowJar {
    archiveFileName.set("BitwigPal.bwextension")
    destinationDirectory.set(file(bitwigExtensionsDir))
    dependsOn(copyDocsToResources)
    from(layout.buildDirectory.dir("resources/main"))
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}

// --- CLI source set ---

sourceSets {
    create("cli") {
        java.srcDir("src/cli/java")
    }
}

val cliImplementation by configurations.getting
val cliRuntimeOnly by configurations.getting

dependencies {
    cliImplementation(libs.picocli)
    cliImplementation(libs.gson)
    cliImplementation(libs.java.websocket)

    // CLI classes available in tests
    testImplementation(sourceSets["cli"].output)
    testImplementation(libs.picocli)
}

tasks.register<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("cliShadowJar") {
    from(sourceSets["cli"].output)
    configurations = listOf(project.configurations["cliRuntimeClasspath"])
    archiveFileName.set("bitwig-pal-cli.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    manifest {
        attributes("Main-Class" to "dev.bcrick.bitwigpal.cli.BitwigPalCli")
    }
    mergeServiceFiles()
}
