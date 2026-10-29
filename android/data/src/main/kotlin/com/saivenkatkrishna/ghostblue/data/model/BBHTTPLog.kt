package com.saivenkatkrishna.ghostblue.data.model

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.saivenkatkrishna.ghostblue.kit.model.HTTPLog
import java.time.Instant
import java.util.UUID

/** Which part of the app made a request. Raw values match iOS, plus Android's own. */
enum class DeviceType(val rawValue: String, val displayName: String) {
    PHONE("Phone", "Phone"),
    TABLET("Tablet", "Tablet"),
    WIDGET("Widget", "Widget"),
    WATCH("Watch", "Watch"),
    LIVE_ACTIVITY("Live Activity", "Live Activity");

    companion object {
        fun fromRawValue(raw: String): DeviceType? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** A logged API request, shown in Settings › HTTP Logs. */
@Entity(tableName = "http_logs", indices = [Index("timestamp")])
class BBHTTPLog() {
    @PrimaryKey var id: UUID = UUID.randomUUID()
    lateinit var log: HTTPLog

    /** Copied from [log] so the cleanup can sort without decoding. */
    var timestamp: Instant = Instant.now()
    var deviceTypeRaw: String = DeviceType.PHONE.rawValue

    @Ignore
    constructor(log: HTTPLog, deviceType: DeviceType = DeviceType.PHONE) : this() {
        this.log = log
        timestamp = log.timestamp
        deviceTypeRaw = deviceType.rawValue
    }

    val deviceType: DeviceType get() = DeviceType.fromRawValue(deviceTypeRaw) ?: DeviceType.PHONE
}
