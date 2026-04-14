plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.abook"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.abook"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

// After every APK build, copy the APK to the project root and rename it to
// "abook-<buildType>.apk" so it's easy to find without digging through
// build/outputs/apk/<type>/. This matches the historical workflow.
// Using a plain doLast action (rather than a Copy task) avoids Gradle's
// input/output tracking conflicts between this output directory and other
// tasks that write into build/outputs/apk/.
androidComponents {
    onVariants { variant ->
        val capitalized = variant.name.replaceFirstChar { it.uppercase() }
        tasks.matching { it.name == "assemble${capitalized}" }.configureEach {
            doLast {
                val apkDir = layout.buildDirectory
                    .dir("outputs/apk/${variant.name}").get().asFile
                val src = apkDir.listFiles { _, n -> n.endsWith(".apk") }?.firstOrNull()
                if (src != null && src.exists()) {
                    val dst = File(rootProject.projectDir, "abook-${variant.name}.apk")
                    src.copyTo(dst, overwrite = true)
                    logger.lifecycle("Copied APK -> ${dst.absolutePath}")
                }
            }
        }
    }
}

dependencies {
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    // Navigation
    implementation(libs.navigation.compose)

    // Lifecycle
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Media
    implementation(libs.media)

    // Coroutines
    implementation(libs.coroutines.android)

    // DataStore
    implementation(libs.datastore.preferences)

    // Parsing
    implementation(libs.jsoup)
    implementation(libs.itextpdf.kernel)

    // Core
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)

    // Testing
    testImplementation(libs.junit)
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
