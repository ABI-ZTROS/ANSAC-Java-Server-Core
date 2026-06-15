plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "dev.ztros.ansac"
version = "1.0.0-SNAPSHOT"

description = "ANSAC - Advanced Network Security Anti-Cheat for Folia"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-public/")
    maven("https://repo.nexomc.com/releases/")
}

dependencies {
    compileOnly("dev.folia:folia-api:1.20.1-R0.1-SNAPSHOT")
    compileOnly("com.github.retrooper:packetevents-api:2.7.0")
    compileOnly("com.github.retrooper:packetevents-spigot:2.7.0")

    implementation("com.tcoded:FoliaLib:0.4.3")

    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(17)
    }

    shadowJar {
        archiveClassifier.set("")
        archiveFileName.set("ANSAC-AntiCheat-${project.version}.jar")

        relocate("com.tcoded.folialib", "dev.ztros.ansac.lib.folialib")

        minimize {
            exclude(dependency("com.tcoded:FoliaLib:.*"))
        }
    }

    build {
        dependsOn(shadowJar)
    }
}
