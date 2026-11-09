allprojects {
    group = "io.github.tmjoris.assay"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial,-classfile", "-Werror"))
    }
}
