plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.saivenkatkrishna.ghostblue.wear"
    compileSdk = 37

    defaultConfig {
        // The phone app's id, so the two can talk over Wear OS's Data Layer.
        applicationId = "com.saivenkatkrishna.ghostblue"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric's Android 16 sandbox reaches into JDK internals.
        unitTests.all {
            it.jvmArgs("--add-exports", "java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
}

dependencies {
    implementation(project(":betterbluekit"))
    implementation(project(":data"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.material)
    implementation(libs.wear.protolayout.expression)
    implementation(libs.wear.complications.data.source)
    implementation(libs.wear.input)
    implementation(libs.play.services.wearable)
    implementation(libs.concurrent.futures.ktx)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.wear.tiles.renderer)
    testImplementation(libs.wear.tiles.testing)
    debugImplementation(libs.compose.ui.test.manifest)
}
