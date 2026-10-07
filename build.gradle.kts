plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
}

group = "jonyboylovespie.jade"
version = "0.6.0"
description = "Paper plugin that provides Jade server-side support for Fabric clients"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

dependencies {
    paperweight.paperDevBundle("26.3.build.+")
}

tasks {
    compileJava {
        options.release = 25
        options.encoding = Charsets.UTF_8.name()
    }
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
    jar {
        archiveFileName = "JadePlugin-${project.version}+26.3.jar"
    }
}

// In-server self test: a second plugin that checks this plugin's payloads against the real Jade 26.3 codecs.
// Build with `./gradlew selftestJar`, drop both jars into a Paper 26.3 server and look for "[JadeSelfTest]" in the log.
repositories {
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") }
        filter { includeGroup("maven.modrinth") }
    }
}
val selftest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
}
val jade: Configuration by configurations.creating {
    isTransitive = false
}
dependencies {
    jade("maven.modrinth:jade:26.3.5+fabric")
}
configurations[selftest.compileOnlyConfigurationName].extendsFrom(jade)
tasks.named<JavaCompile>("compileSelftestJava") {
    options.release = 25
    options.encoding = Charsets.UTF_8.name()
}
tasks.register<Jar>("selftestJar") {
    archiveFileName = "JadeSelfTest.jar"
    destinationDirectory = layout.buildDirectory.dir("selftest")
    from(selftest.output)
    from(jade.elements.map { jars -> jars.map { zipTree(it) } }) {
        include("snownee/**")
    }
}
