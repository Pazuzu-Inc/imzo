plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
}

// One source of truth: the shared protocol test data lives in <repo>/protocol and is only
// exposed to the tests as classpath resources, never copied into the source tree.
tasks.processTestResources {
    from(rootProject.layout.projectDirectory.dir("../protocol")) {
        include("cases.json", "vectors.json")
    }
}
