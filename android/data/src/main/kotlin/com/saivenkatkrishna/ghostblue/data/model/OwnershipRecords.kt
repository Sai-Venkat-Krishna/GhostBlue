package com.saivenkatkrishna.ghostblue.data.model

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID

enum class CommandActivityState(val rawValue: String, val title: String) {
    SENDING("sending", "Sending"),
    SUCCEEDED("succeeded", "Completed"),
    FAILED("failed", "Failed");

    companion object {
        fun fromRawValue(raw: String): CommandActivityState? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** One remote command and how it ended, for the activity log. */
@Entity(tableName = "remote_commands", indices = [Index("vin"), Index("startedAt")])
class RemoteCommandActivity() {
    @PrimaryKey var id: UUID = UUID.randomUUID()
    var vin: String = ""
    var vehicleName: String = ""
    var commandName: String = ""
    var sourceName: String = ""
    var stateRaw: String = CommandActivityState.SENDING.rawValue
    var startedAt: Instant = Instant.now()
    var completedAt: Instant? = null
    var errorMessage: String? = null

    @Ignore
    constructor(vin: String, vehicleName: String, commandName: String, sourceName: String) : this() {
        this.vin = vin
        this.vehicleName = vehicleName
        this.commandName = commandName
        this.sourceName = sourceName
    }

    var state: CommandActivityState
        get() = CommandActivityState.fromRawValue(stateRaw) ?: CommandActivityState.SENDING
        set(value) {
            stateRaw = value.rawValue
        }

    fun finish(state: CommandActivityState, error: Throwable? = null, at: Instant = Instant.now()) {
        this.state = state
        completedAt = at
        errorMessage = error?.message
    }
}

/** A service reminder, due by date or odometer. */
@Entity(tableName = "maintenance_reminders", indices = [Index("vin")])
class MaintenanceReminder() {
    @PrimaryKey var id: UUID = UUID.randomUUID()
    var vin: String = ""
    var title: String = ""
    var notes: String = ""
    var dueDate: Instant? = null
    var dueOdometer: Double? = null
    var cost: Double? = null
    var createdAt: Instant = Instant.now()
    var completedAt: Instant? = null
    var lastNotifiedAt: Instant? = null

    @Ignore
    constructor(
        vin: String,
        title: String,
        notes: String = "",
        dueDate: Instant? = null,
        dueOdometer: Double? = null,
        cost: Double? = null,
    ) : this() {
        this.vin = vin
        this.title = title
        this.notes = notes
        this.dueDate = dueDate
        this.dueOdometer = dueOdometer
        this.cost = cost
    }

    val isCompleted: Boolean get() = completedAt != null
}

enum class VehicleAutomationKind(val rawValue: String, val title: String) {
    LOW_BATTERY("lowBattery", "Low battery reminder"),
    UNPLUGGED("unplugged", "Unplugged reminder"),
    PRECONDITION_REMINDER("preconditionReminder", "Precondition reminder");

    companion object {
        fun fromRawValue(raw: String): VehicleAutomationKind? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** A per-car rule: alert on low battery or unplugging, or a daily precondition reminder. */
@Entity(tableName = "automations", indices = [Index("vin")])
class VehicleAutomation() {
    @PrimaryKey var id: UUID = UUID.randomUUID()
    var vin: String = ""
    var title: String = ""
    var kindRaw: String = VehicleAutomationKind.LOW_BATTERY.rawValue
    var isEnabled: Boolean = true
    var threshold: Int = 20
    var hour: Int = 7
    var minute: Int = 30
    var lastTriggeredAt: Instant? = null
    var createdAt: Instant = Instant.now()

    @Ignore
    constructor(
        vin: String,
        title: String,
        kind: VehicleAutomationKind,
        threshold: Int = 20,
        hour: Int = 7,
        minute: Int = 30,
    ) : this() {
        this.vin = vin
        this.title = title
        kindRaw = kind.rawValue
        this.threshold = threshold
        this.hour = hour
        this.minute = minute
    }

    var kind: VehicleAutomationKind
        get() = VehicleAutomationKind.fromRawValue(kindRaw) ?: VehicleAutomationKind.LOW_BATTERY
        set(value) {
            kindRaw = value.rawValue
        }
}
