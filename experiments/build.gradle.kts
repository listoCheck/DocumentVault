plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(21) }
application { mainClass.set("ru.documentvault.experiments.SearchExperimentKt") }
dependencies { implementation(project(":core")) }
