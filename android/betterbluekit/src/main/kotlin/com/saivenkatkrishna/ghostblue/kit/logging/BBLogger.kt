package com.saivenkatkrishna.ghostblue.kit.logging

enum class BBLogLevel(val emoji: String) {
    DEBUG("🔍"),
    INFO("ℹ️"),
    WARNING("⚠️"),
    ERROR("❌"),
}

enum class BBLogCategory(val rawValue: String) {
    API("API"),
    AUTH("Auth"),
    MFA("MFA"),
    LIVE_ACTIVITY("LiveActivity"),
    INTENT("Intent"),
    BACKGROUND("Background"),
    PUSH("Push"),
    APP("App"),
    VEHICLE("Vehicle"),
    FAKE_API("FakeAPI"),
}

fun interface BBLogSink {
    fun log(level: BBLogLevel, category: BBLogCategory, message: String)
}

/** Prints to standard output; the default until the app installs its own sink. */
object BBPrintLogSink : BBLogSink {
    override fun log(level: BBLogLevel, category: BBLogCategory, message: String) {
        println("${level.emoji} [${category.rawValue}] $message")
    }
}

/** Global logger; set [sink] at startup (the Android app routes it to Logcat). */
object BBLogger {
    @Volatile
    var sink: BBLogSink = BBPrintLogSink

    fun debug(category: BBLogCategory, message: String) = sink.log(BBLogLevel.DEBUG, category, message)
    fun info(category: BBLogCategory, message: String) = sink.log(BBLogLevel.INFO, category, message)
    fun warning(category: BBLogCategory, message: String) = sink.log(BBLogLevel.WARNING, category, message)
    fun error(category: BBLogCategory, message: String) = sink.log(BBLogLevel.ERROR, category, message)
}
