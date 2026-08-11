// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt)
}

detekt {
    // Start from detekt's default ruleset and layer the project config on top, rather than
    // replacing it wholesale -- that way new rules from a detekt upgrade are picked up.
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))

    // Resolved from the module list rather than hardcoded, so a new module is covered the moment
    // it is added to settings.gradle.kts. Directories that do not exist are skipped.
    source.setFrom(
        subprojects.flatMap { module ->
            listOf(
                "${module.projectDir}/src/main/java",
                "${module.projectDir}/src/main/kotlin",
                "${module.projectDir}/src/test/java",
                "${module.projectDir}/src/test/kotlin",
            )
        }
    )
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        sarif.required.set(true) // uploaded to GitHub code scanning by the CI workflow
        xml.required.set(false)
        txt.required.set(false)
        md.required.set(false)
    }
}
