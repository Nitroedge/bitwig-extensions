plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "bitwig-extensions"

include("bitwig-pal", "launchpad-mk2")
