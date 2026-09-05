import org.gradle.api.artifacts.ProjectDependency

// Deklarasi plugin di root (apply false) supaya Kotlin plugin dimuat sekali di
// classpath root, lalu subproject tinggal meng-apply tanpa memuat ulang.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.spring.boot) apply false
}

allprojects {
    group = "com.duluin.ftth"
    version = "0.1.0-SNAPSHOT"

    repositories {
        google()
        mavenCentral()
    }
}

val verifyMobileModuleGraph = tasks.register("verifyMobileModuleGraph") {
    group = "verification"
    description = "Rejects forbidden dependencies in the Compose Multiplatform mobile graph."
    doLast {
        val edgesByModule = (inputs.properties["mobileModuleEdges"] as String)
            .lineSequence()
            .filter(String::isNotBlank)
            .associate { line ->
                val (path, edges) = line.split("=", limit = 2)
                path to edges.split(",").filter(String::isNotBlank).toSet()
            }
        edgesByModule.forEach { (path, edges) ->
            when {
                path == ":mobile:domain" && edges.isNotEmpty() ->
                    error(":mobile:domain must not depend on another mobile module: $edges")
                path == ":mobile:core:mvi" && edges.isNotEmpty() ->
                    error(":mobile:core:mvi must remain platform and feature independent: $edges")
                path == ":mobile:core:ui" && edges.isNotEmpty() ->
                    error(":mobile:core:ui must contain only visual primitives: $edges")
                path.startsWith(":mobile:feature:") && edges.any { it.startsWith(":mobile:feature:") } ->
                    error("Feature-to-feature dependencies are forbidden in $path: $edges")
                path.startsWith(":mobile:feature:") && edges.any {
                    it != ":mobile:domain" && !it.startsWith(":mobile:core:")
                } -> error("Feature dependencies must target domain or core modules in $path: $edges")
            }
        }
    }
}

gradle.projectsEvaluated {
    val encodedEdges = rootProject.allprojects
        .filter { it.path.startsWith(":mobile:") }
        .associate { mobileProject ->
            mobileProject.path to mobileProject.configurations
                .flatMap { configuration -> configuration.dependencies.filterIsInstance<ProjectDependency>() }
                .map { dependency: ProjectDependency -> dependency.path }
                .toSet()
        }
        .toSortedMap()
        .entries
        .joinToString("\n") { (path, edges) -> "$path=${edges.sorted().joinToString(",")}" }
    verifyMobileModuleGraph.configure {
        inputs.property("mobileModuleEdges", encodedEdges)
    }
}
