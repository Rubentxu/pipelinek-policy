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
        // M6: the published PipelineK SDK contracts (0.47.0) live in mavenLocal
        // (publishToMavenLocal from pipeline-kotlin branch s6-plugin-sdk). No other
        // module may resolve them: the plugin bucket allowlist enforces that.
        mavenLocal()
    }
}

// M2 WU-2 + WU-3: parser submodules per ADR-0011 D11.1. csv + map are
// hand-rolled on top of the kotlin-stdlib bucket only. Removing an entry
// here shrinks the parsers bucket in the architectureFitnessGuard.
include(
    "policy-decoders-json",
    "policy-decoders-yaml",
    "policy-decoders-csv",
    "policy-decoders-map",
    "pipelinek-policy-plugin",
    "pipelinek-policy-cli",
)