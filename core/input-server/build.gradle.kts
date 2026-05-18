plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

val androidSdkDir = System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: throw GradleException("ANDROID_HOME not set")

val androidJar = file("$androidSdkDir/platforms/android-34/android.jar")
if (!androidJar.exists()) {
    throw GradleException("android.jar not found at ${androidJar.absolutePath}")
}

dependencies {
    compileOnly(files(androidJar))
}

tasks.register("buildDex") {
    dependsOn("classes")
    val buildToolsVer = "34.0.0"
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    val d8Path = if (isWindows) {
        "$androidSdkDir/build-tools/$buildToolsVer/d8.bat"
    } else {
        "$androidSdkDir/build-tools/$buildToolsVer/d8"
    }

    val classesDir = layout.buildDirectory.dir("classes/java/main").get().asFile.absolutePath
    val outputDir = layout.buildDirectory.dir("dex").get().asFile.absolutePath

    doFirst { mkdir(outputDir) }

    doLast {
        val classesFile = File(classesDir)
        val classFiles = classesFile.walkTopDown()
            .filter { it.extension == "class" }
            .toList()

        if (classFiles.isEmpty()) {
            throw GradleException("No class files found in $classesDir")
        }

        exec {
            executable(d8Path)
            args("--min-api", "24", "--output", outputDir)
            args(classFiles.map { it.absolutePath })
        }

        val targetJar = file("../../app/src/main/assets/input-server.jar")
        targetJar.parentFile.mkdirs()

        val dexFiles = File(outputDir).listFiles()?.filter { it.extension == "dex" } ?: emptyList()
        if (dexFiles.isEmpty()) {
            throw GradleException("No DEX files generated in $outputDir")
        }

        ant.withGroovyBuilder {
            "zip"("destfile" to targetJar, "basedir" to outputDir)
        }
        println("Payload injected to App Assets: ${targetJar.absolutePath}")
    }
}
