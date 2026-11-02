plugins { kotlin("jvm"); application }

kotlin { jvmToolchain(21) }
application { mainClass.set("ru.documentvault.desktop.DesktopKt") }

dependencies {
    implementation(project(":core"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")
    implementation("net.sourceforge.tess4j:tess4j:5.13.0")
    implementation("org.apache.pdfbox:pdfbox:3.0.5")
    testImplementation(kotlin("test"))
    testImplementation(project(":server"))
    testImplementation("io.javalin:javalin:7.2.4")
}
tasks.test { useJUnitPlatform() }
