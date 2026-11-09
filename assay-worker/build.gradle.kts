val spark = "4.0.2"
val iceberg = "1.11.0"
val comet = "1.2.0"

val sparkRuntime: Configuration by configurations.creating
val cometRuntime: Configuration by configurations.creating { extendsFrom(sparkRuntime) }
val veloxRuntime: Configuration by configurations.creating { extendsFrom(sparkRuntime) }

val glutenJars = fileTree(rootDir.resolve(".engines/gluten-1.7.0")) { include("*.jar") }

dependencies {
    implementation(project(":assay-core"))
    compileOnly("org.apache.spark:spark-sql_2.13:$spark")

    sparkRuntime(project(":assay-core"))
    sparkRuntime("org.apache.spark:spark-sql_2.13:$spark")
    sparkRuntime("org.apache.iceberg:iceberg-spark-runtime-4.0_2.13:$iceberg")

    cometRuntime("org.apache.datafusion:comet-spark-spark4.0_2.13:$comet")

    // Gluten's Spark integration loads Hive classes during class initialisation.
    veloxRuntime("org.apache.spark:spark-hive_2.13:$spark")
    veloxRuntime(glutenJars)
}

val fetchGluten by tasks.registering(Exec::class) {
    commandLine(rootDir.resolve("scripts/fetch-gluten.sh").path)
}

val writeClasspaths by tasks.registering {
    dependsOn(tasks.jar, fetchGluten)
    val outDir = layout.buildDirectory.dir("classpaths")
    val workerJar = tasks.jar.flatMap { it.archiveFile }
    val runtimes = mapOf(
        "spark" to sparkRuntime,
        "spark-interpreted" to sparkRuntime,
        "comet" to cometRuntime,
        "velox" to veloxRuntime,
    )
    doLast {
        val dir = outDir.get().asFile
        dir.mkdirs()
        runtimes.forEach { (engine, config) ->
            val entries = listOf(workerJar.get().asFile) + config.resolve()
            dir.resolve("$engine.txt").writeText(entries.joinToString(File.pathSeparator))
        }
    }
}
