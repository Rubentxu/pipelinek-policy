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

// M2 WU-2 + WU-3: parser submodules per ADR-0011 D11.1. csv + map are
// hand-rolled on top of the kotlin-stdlib bucket only. Removing an entry
// here shrinks the parsers bucket in the architectureFitnessGuard.
// M4.A (spike): `policy-fir-plugin` opt-in third bucket per design A1 + ADR-0011
// D11.1 extension; recognised ONLY for that project path by the guard.
include(
    "policy-decoders-json",
    "policy-decoders-yaml",
    "policy-decoders-csv",
    "policy-decoders-map",
    "policy-fir-plugin",
)