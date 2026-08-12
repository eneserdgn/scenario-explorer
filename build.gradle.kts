plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.3.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val type = providers.gradleProperty("platformType")
        val version = providers.gradleProperty("platformVersion")
        create(type, version)
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    implementation("com.google.code.gson:gson:2.11.0")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.enesreport"
        name = "Enes Report"
        version = providers.gradleProperty("pluginVersion").get()
        description = """
            Cucumber and Gauge scenario explorer with report integration.
            Browse scenarios in a tree view, view steps, see report results, run scenarios, and export HTML reports.
        """.trimIndent()

        ideaVersion {
            sinceBuild = providers.gradleProperty("sinceBuildVersion").get()
        }

        vendor {
            name = "Enes"
        }

        changeNotes = """
            <b>1.3.0</b><br>
            <ul>
                <li>Fixed a failing Before/After hook not being surfaced as an error — it now shows up as a failed step with its full error message in the Errors tab</li>
                <li>Errors tab: each error group now has an "Add to Pipeline" button to send its affected scenarios straight into the active pipeline</li>
            </ul>
            <b>1.2.0</b><br>
            <ul>
                <li>Scenarios tree: total and average duration shown for folders and features, right-aligned to the panel edge</li>
                <li>Long folder/feature/scenario names are truncated with an ellipsis instead of overflowing</li>
                <li>Visual hierarchy: folder rows are bold/accent-colored, feature rows use a smaller duration style, so levels are easier to tell apart</li>
                <li>Pass/fail counts always shown for folders and features, including zero counts</li>
                <li>Removed the "Skipped" status — scenarios previously marked Skipped now show as Not Run</li>
                <li>Removed feature-level tag display from the Scenarios tree</li>
                <li>All duration displays now consistently start from hours (e.g. 00h 05m 12s)</li>
            </ul>
            <b>1.1.0</b><br>
            <ul>
                <li>Scrollable run history tabs with status indicators (color + icon)</li>
                <li>Terminal area auto-hides when no runs are active</li>
                <li>Fixed concurrent run class file conflicts — each run uses a fully isolated build directory</li>
                <li>Improved error message panel minimum height in Errors tab</li>
                <li>Cleaner toolbar — removed redundant labels and retry button</li>
            </ul>
            <b>1.0.0</b><br>
            <ul>
                <li>Cucumber (.feature) and Gauge (.spec/.cspec) scenario explorer</li>
                <li>Test report integration with pass/fail/skip status</li>
                <li>Run and retry scenarios directly from the IDE</li>
                <li>Dashboard, Steps, Pipeline and Errors tabs</li>
                <li>HTML report export</li>
            </ul>
        """.trimIndent()
    }

    publishing {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = providers.gradleProperty("javaVersion").get()
        targetCompatibility = providers.gradleProperty("javaVersion").get()
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = providers.gradleProperty("javaVersion").get()
    }

    // Skip instrumentCode if it causes issues with non-standard JDK layouts
    named("instrumentCode") {
        enabled = false
    }
}
