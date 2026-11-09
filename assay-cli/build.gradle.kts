plugins {
    application
}

dependencies {
    implementation(project(":assay-core"))
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.16")
}

application {
    mainClass.set("io.github.tmjoris.assay.cli.Main")
    applicationDefaultJvmArgs = listOf("-Xmx1g", "--add-opens=java.base/java.nio=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    dependsOn(":assay-worker:writeClasspaths")
    workingDir = rootDir
}
