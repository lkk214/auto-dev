// The MIT License (MIT)
//
//Copyright (c) 2015 Aleksey Kladov, Evgeny Kurbatsky, Alexey Kudinkin and contributors
//Copyright (c) 2016 JetBrains
//
//Permission is hereby granted, free of charge, to any person obtaining a copy
//of this software and associated documentation files (the "Software"), to deal
//in the Software without restriction, including without limitation the rights
//to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
//copies of the Software, and to permit persons to whom the Software is
//furnished to do so, subject to the following conditions:
//
//The above copyright notice and this permission notice shall be included in all
//copies or substantial portions of the Software.
//
//THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
//IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
//FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
//AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
//LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
//OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
//SOFTWARE.

import groovy.xml.XmlParser
import org.gradle.api.JavaVersion.VERSION_17
import org.jetbrains.intellij.tasks.PatchPluginXmlTask
import org.jetbrains.intellij.tasks.PublishPluginTask
import org.jetbrains.intellij.tasks.RunIdeTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java") // Java support
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.qodana) // Gradle Qodana Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin
    alias(libs.plugins.serialization)

    kotlin("jvm") version "1.8.22"
    id("org.jetbrains.intellij") version "1.13.1"
    id("net.saliman.properties") version "1.5.2"
}

fun properties(key: String) = providers.gradleProperty(key)
fun environment(key: String) = providers.environmentVariable(key)

fun prop(name: String): String =
    extra.properties[name] as? String
        ?: error("Property `$name` is not defined in gradle.properties")

val basePluginArchiveName = "intellij-autodev"

val javaScriptPlugins = listOf("JavaScript")
val pycharmPlugins = listOf("PythonCore")
val javaPlugins = listOf("com.intellij.java")
val kotlinPlugins = listOf("org.jetbrains.kotlin")

val pluginProjects: List<Project> get() = rootProject.allprojects.toList()
val ideaPlugins = listOf("Git4Idea", "com.intellij.java", "org.jetbrains.plugins.gradle")

val baseIDE = prop("baseIDE")
val platformVersion = prop("globalPlatformVersion").toInt()
val ideaVersion = prop("ideaVersion")
val pycharmVersion = prop("pycharmVersion")


val baseVersion = when (baseIDE) {
    "idea" -> ideaVersion
    "pycharm" -> pycharmVersion
//    "webstorm" -> prop("webstormVersion")
    else -> error("Unexpected IDE name: `$baseIDE`")
}

allprojects {
    apply {
        plugin("idea")
        plugin("kotlin")
        plugin("org.jetbrains.intellij")
    }

    repositories {
        mavenCentral()
        maven("https://cache-redirector.jetbrains.com/repo.maven.apache.org/maven2")
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    }

    idea {
        module {
            generatedSourceDirs.add(file("src/gen"))
        }
    }

    intellij {
        version.set(baseVersion)
        updateSinceUntilBuild.set(true)
        instrumentCode.set(false)
        sandboxDir.set("$buildDir/$baseIDE-sandbox-$platformVersion")
    }

    configure<JavaPluginExtension> {
        sourceCompatibility = VERSION_17
        targetCompatibility = VERSION_17
    }

    tasks {
        withType<KotlinCompile> {
            kotlinOptions {
                jvmTarget = VERSION_17.toString()
                languageVersion = "1.8"
                // see https://plugins.jetbrains.com/docs/intellij/using-kotlin.html#kotlin-standard-library
                apiVersion = "1.7"
                freeCompilerArgs = listOf("-Xjvm-default=all")
            }
        }

        withType<PatchPluginXmlTask> {
            sinceBuild.set(prop("pluginSinceBuild"))
            untilBuild.set(prop("pluginUntilBuild"))
        }

        // All these tasks don't make sense for non-root subprojects
        // Root project (i.e. `:plugin`) enables them itself if needed
        runIde { enabled = false }
        prepareSandbox { enabled = false }
        buildSearchableOptions { enabled = false }
    }

    val testOutput = configurations.create("testOutput")

    dependencies {
        compileOnly(kotlin("stdlib-jdk8"))
        testOutput(sourceSets.getByName("test").output.classesDirs)
    }
}

project(":plugin") {
    version = prop("pluginVersion")

    intellij {
        pluginName.set(basePluginArchiveName)
        val pluginList: MutableList<String> = mutableListOf("Git4Idea")
        if (baseIDE == "idea") {
            pluginList += javaPlugins
        }
        plugins.set(pluginList)
    }

    dependencies {
        implementation(project(":"))
        implementation(project(":idea"))
        implementation(project(":pycharm"))
        implementation(project(":webstorm"))
    }

    // Collects all jars produced by compilation of project modules and merges them into singe one.
    // We need to put all plugin manifest files into single jar to make new plugin model work
    val mergePluginJarTask = task<Jar>("mergePluginJars") {
        duplicatesStrategy = DuplicatesStrategy.FAIL
        archiveBaseName.set(basePluginArchiveName)

        exclude("META-INF/MANIFEST.MF")
        exclude("**/classpath.index")

        val pluginLibDir by lazy {
            val sandboxTask = tasks.prepareSandbox.get()
            sandboxTask.destinationDir.resolve("${sandboxTask.pluginName.get()}/lib")
        }

        val pluginJars by lazy {
            pluginLibDir.listFiles().orEmpty().filter {
                it.isPluginJar()
            }
        }

        destinationDirectory.set(project.layout.dir(provider { pluginLibDir }))

        doFirst {
            for (file in pluginJars) {
                from(zipTree(file))
            }
        }

        doLast {
            delete(pluginJars)
        }
    }

    // Add plugin sources to the plugin ZIP.
    // gradle-intellij-plugin will use it as a plugin sources if the plugin is used as a dependency
    val createSourceJar = task<Jar>("createSourceJar") {
        for (prj in pluginProjects) {
            from(prj.kotlin.sourceSets.main.get().kotlin) {
                include("**/*.java")
                include("**/*.kt")
            }
        }

        destinationDirectory.set(layout.buildDirectory.dir("libs"))
        archiveBaseName.set(basePluginArchiveName)
        archiveClassifier.set("src")
    }

    tasks {
        buildPlugin {
            dependsOn(createSourceJar)
            from(createSourceJar) { into("lib/src") }
            // Set proper name for final plugin zip.
            // Otherwise, base name is the same as gradle module name
            archiveBaseName.set(basePluginArchiveName)
        }

        runIde { enabled = true }

        prepareSandbox {
            finalizedBy(mergePluginJarTask)
            enabled = true
        }

        buildSearchableOptions {
            // Force `mergePluginJarTask` be executed before `buildSearchableOptions`
            // Otherwise, `buildSearchableOptions` task can't load the plugin and searchable options are not built.
            // Should be dropped when jar merging is implemented in `gradle-intellij-plugin` itself
            dependsOn(mergePluginJarTask)
            enabled = false
        }

        withType<RunIdeTask> {
            // Default args for IDEA installation
            jvmArgs("-Xmx768m", "-XX:+UseG1GC", "-XX:SoftRefLRUPolicyMSPerMB=50")
            // Disable plugin auto reloading. See `com.intellij.ide.plugins.DynamicPluginVfsListener`
            jvmArgs("-Didea.auto.reload.plugins=false")
            // Don't show "Tip of the Day" at startup
            jvmArgs("-Dide.show.tips.on.startup.default.value=false")
            // uncomment if `unexpected exception ProcessCanceledException` prevents you from debugging a running IDE
            // jvmArgs("-Didea.ProcessCanceledException=disabled")
        }

        withType<PatchPluginXmlTask> {
            pluginDescription.set(provider { file("description.html").readText() })
        }

        withType<PublishPluginTask> {
            token.set("publishToken")
            channels.set(properties("pluginVersion").map {
                listOf(
                    it.split('-').getOrElse(1) { "default" }.split('.').first()
                )
            })
        }
    }
}

project(":") {
    intellij {
        version.set(ideaVersion)
        plugins.set(listOf("Git4Idea"))
    }

    dependencies {
        implementation(libs.github.api)
        implementation(libs.dotenv)

        implementation(libs.bundles.openai)
        implementation(libs.bundles.markdown)

        implementation(libs.kotlinx.serialization.json)
        // jackson-module-kotlin
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.14.2") {
            exclude(module = "jackson-core")
            exclude(module = "jackson-databind")
            exclude(module = "jackson-annotations")
        }

        implementation("org.archguard.comate:spec-lang:0.2.0") {
            exclude(module = "jackson-core")
            exclude(module = "jackson-databind")
            exclude(module = "jackson-annotations")
        }

        implementation("com.knuddels:jtokkit:0.6.1")

        // junit
        testImplementation("junit:junit:4.13.2")
        testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.9.3")
    }

    task("resolveDependencies") {
        doLast {
            rootProject.allprojects
                .map { it.configurations }
                .flatMap { it.filter { c -> c.isCanBeResolved } }
                .forEach { it.resolve() }
        }
    }
}

project(":pycharm") {
    intellij {
        version.set(pycharmVersion)
        plugins.set(pycharmPlugins)
    }
    dependencies {
        implementation(project(":"))
    }
}

project(":idea") {
    intellij {
        version.set(ideaVersion)
        plugins.set(ideaPlugins)
    }
    dependencies {
        implementation(project(":"))
    }
}

project(":webstorm") {
    intellij {
        plugins.set(javaScriptPlugins)
    }
    dependencies {
        implementation(project(":"))
    }
}

fun File.isPluginJar(): Boolean {
    if (!isFile) return false
    if (extension != "jar") return false
    return zipTree(this).files.any { it.isManifestFile() }
}

fun File.isManifestFile(): Boolean {
    if (extension != "xml") return false
    val rootNode = try {
        val parser = XmlParser()
        parser.parse(this)
    } catch (e: Exception) {
        logger.error("Failed to parse $path", e)
        return false
    }
    return rootNode.name() == "idea-plugin"
}


