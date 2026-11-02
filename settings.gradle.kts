pluginManagement { repositories { google(); gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "DocumentVault"
include(":core")
include(":server")
include(":desktopApp")
include(":experiments")
