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
