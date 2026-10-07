rootProject.name = "pipelinek-policy"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

// M2 WU-2: policy-decoders-json + policy-decoders-yaml are included as
// opt-in parser submodules per ADR-0011 D11.1. policy-decoders-csv and
// policy-decoders-map land in WU-3. Removing an entry here shrinks the
// parsers bucket in the architectureFitnessGuard accordingly.
include("policy-decoders-json", "policy-decoders-yaml")