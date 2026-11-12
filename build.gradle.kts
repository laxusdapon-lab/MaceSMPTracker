plugins {
    java
}

group = "dev.macesmp"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Resolves to the newest build of Paper 26.3. If Gradle cannot find it, open
    // https://repo.papermc.io/service/rest/repository/browse/maven-public/io/papermc/paper/paper-api/
    // and copy the exact 26.3 version (for example "26.3.build.12-alpha") here.
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.jar {
    archiveBaseName.set("MaceSMPTracker")
}
tasks.test {
    failOnNoDiscoveredTests = false
}
