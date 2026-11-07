// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.spotless) apply false
}

// Formatting is checked with ktlint through Spotless. `ratchetFrom` limits the check to files
// changed since main, so adopting it does not require reformatting the whole codebase at once.
// Run `./gradlew spotlessApply` before pushing, or `spotlessCheck` to see the diff.
allprojects {
    apply(plugin = "com.diffplug.spotless")

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        ratchetFrom("origin/main")
        kotlin {
            target("src/**/*.kt")
            ktlint(libs.versions.ktlint.get())
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(libs.versions.ktlint.get())
        }
    }
}
