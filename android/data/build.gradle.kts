plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.saivenkatkrishna.ghostblue.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric's Android 16 sandbox reaches into JDK internals.
        unitTests.all {
            it.jvmArgs("--add-exports", "java.base/jdk.internal.access=ALL-UNNAMED")
            // tools/parity/app-parity.sh: where to write the backup the iOS code imports.
            providers.gradleProperty("parityOut").orNull?.let { dir -> it.systemProperty("ghostblue.parityOut", dir) }
        }
    }
}

// Exported schemas: migrations are written against these.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":betterbluekit"))
    api(libs.room.runtime)
    ksp(libs.room.compiler)

    // Room runs under Robolectric, so the tests need no emulator.
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
