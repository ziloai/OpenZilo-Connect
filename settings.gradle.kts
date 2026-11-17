pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "OpenZiloConnect"
include(":app", ":connect-api", ":ring-core")
listOf("tools/prepare_miuix.py", "tools/miuix-build-directory.patch").forEach {
    providers.fileContents(layout.settingsDirectory.file(it)).asText.get()
}
providers.exec {
    commandLine("python3", "-I", rootDir.resolve("tools/prepare_miuix.py"))
}.standardOutput.asText.get()
includeBuild("vendor/miuix") {
    dependencySubstitution {
        listOf("core", "ui", "preference", "material", "blur", "glass", "glass-icons", "squircle", "icons", "nav", "window").forEach { artifact ->
            substitute(module("top.yukonga.miuix.kmp:miuix-$artifact")).using(project(":miuix-$artifact"))
        }
    }
}
