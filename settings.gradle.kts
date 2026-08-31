rootProject.name = "gridwork"

include("domain")
include("api")
include("worker")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
