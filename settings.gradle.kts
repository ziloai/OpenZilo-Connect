pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                ivy {
                    name = "OpenZiloCoreReleases"
                    url = uri("https://github.com/ziloai/libopenzilo/releases/download")
                    patternLayout {
                        artifact("v[revision]/[artifact]-[revision].[ext]")
                        artifact("android-v[revision]/[artifact]-[revision].[ext]")
                    }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("io.github.ziloai", "libopenzilo-android") }
        }
    }
}
rootProject.name = "OpenZiloConnect"
include(":app", ":connect-api", ":ring-core")
