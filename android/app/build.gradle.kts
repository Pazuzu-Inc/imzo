plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "inc.pazuzu.imzo"
    compileSdk = 35

    defaultConfig {
        applicationId = "inc.pazuzu.imzo"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":protocol"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.biometric)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.zxing.core)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.kotlinx.coroutines.core)
}

// The approver must never be able to talk to a network: fail the build if the merged manifest of
// the built APK declares anything beyond the allowed permissions (see scripts/check_permissions.sh).
val aapt2 = androidComponents.sdkComponents.sdkDirectory.map { sdk ->
    // newest installed build-tools
    sdk.dir("build-tools").asFile.listFiles { f -> f.isDirectory }.orEmpty()
        .maxByOrNull { it.name }!!.resolve("aapt2").absolutePath
}

fun registerCheckPermissions(variant: String) {
    val cap = variant.replaceFirstChar { it.uppercase() }
    val apk = layout.buildDirectory.file(
        if (variant == "release") "outputs/apk/release/app-release-unsigned.apk" else "outputs/apk/$variant/app-$variant.apk",
    )
    val check = tasks.register<Exec>("checkPermissions$cap") {
        group = "verification"
        description = "Fails if the $variant APK requests a permission other than the allowed ones."
        dependsOn("assemble$cap")
        doFirst {
            environment("AAPT2", aapt2.get())
            commandLine("bash", rootProject.file("scripts/check_permissions.sh").absolutePath, apk.get().asFile.absolutePath)
        }
    }
    tasks.matching { it.name == "assemble$cap" }.configureEach { finalizedBy(check) }
}
registerCheckPermissions("debug")
registerCheckPermissions("release")

tasks.register("checkPermissions") {
    group = "verification"
    description = "Builds debug and release and checks both APKs' permissions."
    dependsOn("checkPermissionsDebug", "checkPermissionsRelease")
}
