plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(21) }
application { mainClass.set("ru.documentvault.experiments.SearchExperimentKt") }
dependencies {
    implementation(project(":core"))
    implementation(project(":desktopApp"))
    testImplementation(kotlin("test"))
}
tasks.test { useJUnitPlatform() }
tasks.register<JavaExec>("ocrMetrics") {
    group = "verification"
    description = "Measure CER and WER from a TSV file containing reference and recognized text."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ru.documentvault.experiments.OcrMetricsKt")
    args("data/ocr-synthetic.tsv")
}
tasks.register<JavaExec>("ocrImages") {
    group = "verification"
    description = "Run desktop OCR on images and compare against a reference manifest."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ru.documentvault.experiments.OcrImageExperimentKt")
}
