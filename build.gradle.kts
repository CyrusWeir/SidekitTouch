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
//
// Spotless does not pick up .editorconfig on its own, and without it ktlint falls back to its
// defaults: 140-column wrapping and no Composable naming exemption, so every screen would fail.
// The ktlint rules from .editorconfig are therefore read here and handed over explicitly; the
// file stays the single source of truth for editors and for CI.
val ktlintRulesFromEditorConfig: Map<String, String> =
    providers.fileContents(rootProject.layout.projectDirectory.file(".editorconfig"))
        .asText
        .map { text ->
            text.lines()
                .map { it.substringBefore('#').trim() }
                .filter { '=' in it }
                .map { it.split('=', limit = 2).map(String::trim) }
                .filter { (key, _) -> key == "max_line_length" || key.startsWith("ktlint_") }
                .associate { (key, value) -> key to value }
        }
        .get()

allprojects {
    apply(plugin = "com.diffplug.spotless")

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        // A fork clone or a checkout without that ref can point it elsewhere: -PspotlessRatchet=upstream/main
        ratchetFrom(providers.gradleProperty("spotlessRatchet").getOrElse("origin/main"))
        kotlin {
            target("src/**/*.kt")
            ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintRulesFromEditorConfig)
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintRulesFromEditorConfig)
        }
    }
}
