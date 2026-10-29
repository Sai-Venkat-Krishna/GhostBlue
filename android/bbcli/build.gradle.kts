import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":betterbluekit"))
}

application {
    mainClass.set("com.saivenkatkrishna.ghostblue.bbcli.MainKt")
    applicationName = "bbcli"
}

// `./gradlew :bbcli:run` is interactive: pass the terminal through.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
