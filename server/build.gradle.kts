plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(21) }
application { mainClass.set("ru.documentvault.server.ServerKt") }

dependencies {
    implementation("io.javalin:javalin:7.2.4")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("org.springframework.security:spring-security-crypto:6.5.4")
    implementation("commons-logging:commons-logging:1.3.5")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testImplementation(kotlin("test"))
    testImplementation(project(":core"))
}

tasks.test { useJUnitPlatform() }

tasks.register<JavaExec>("backup") {
    group = "application"
    description = "Create a consistent SQLite backup; pass a new destination path with --args."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ru.documentvault.server.BackupKt")
}
