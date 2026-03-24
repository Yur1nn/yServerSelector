plugins {
    java
}

group = "dev.onelimit.yserverselector"
version = "1.0.0"

base {
    archivesName.set("yserverselector")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")

    implementation("dev.onelimit.ycore:ycore-velocity:1.0.0")

    implementation("org.yaml:snakeyaml:2.2")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}
