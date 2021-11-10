plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("kotlinx-atomicfu")
    id("com.github.johnrengelman.shadow")
}

val jarDeps by configurations.creating {
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
}
configurations.implementation {
    extendsFrom(jarDeps)
}

val drillDsm: String by extra
kotlin.sourceSets.all {
    languageSettings.useExperimentalAnnotation("kotlinx.coroutines.ExperimentalCoroutinesApi")
    languageSettings.useExperimentalAnnotation("kotlinx.serialization.ExperimentalSerializationApi")
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))

    //plugin dependencies
    jarDeps(project(":api"))
    jarDeps(project(":agent-api"))
    jarDeps(project(":jacoco"))
    jarDeps("org.jacoco:org.jacoco.core")
    jarDeps("org.apache.bcel:bcel")

    //provided by drill runtime
    implementation("com.epam.drill:drill-admin-part")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    compileOnly("org.jetbrains.kotlinx:atomicfu")
    compileOnly("com.github.luben:zstd-jni")

    //provided by admin
    //TODO create a platform for admin dependencies
    implementation("com.epam.drill:dsm:$drillDsm")
    implementation("org.jetbrains.exposed:exposed-core:0.29.1")//todo remove it(move to API of dsm)
    implementation("com.epam.drill:kodux")
    implementation("org.jetbrains.xodus:xodus-entity-store")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlinx:atomicfu")
}

tasks {
    test {
        useJUnitPlatform()
    }

    shadowJar {
        destinationDirectory.set(file("$buildDir/shadowLibs"))
        archiveFileName.set("admin-part.jar")
        isZip64 = true
        configurations = listOf(jarDeps)
        dependencies {
            exclude(
                "/META-INF/**",
                "/*.class",
                "/*.html"
            )
        }
        listOf(
            "org.jacoco",
            "org.objectweb"
        ).forEach { relocate(it, "${rootProject.group}.test2code.shadow.$it") }
    }
}
