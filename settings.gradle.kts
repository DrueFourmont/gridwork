rootProject.name = "gridwork"

include("domain")
include("core")
include("api")
include("worker")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
