package com.saivenkatkrishna.ghostblue.widgets

import com.saivenkatkrishna.ghostblue.data.account.RemoteAction
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What a command sent from a widget or tile is doing, for the widgets and
 * tiles to show on the car: otherwise its progress and any failure appear
 * only as a notification, which Android may not allow. Kept in memory: the
 * worker that sends the command runs in the widgets' process, and a new
 * process has nothing in flight.
 */
object CommandFeedback {
    data class Entry(val action: RemoteAction, val text: String, val running: Boolean, val at: Instant)

    private val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())

    /** By car and control ("VIN:lock"). */
    val all: StateFlow<Map<String, Entry>> = entries.asStateFlow()

    private fun key(vin: String, control: String) = "$vin:$control"

    fun show(vin: String, action: RemoteAction, text: String, running: Boolean, now: Instant = Instant.now()): Entry =
        Entry(action, text, running, now).also { entry -> entries.update { it + (key(vin, action.control) to entry) } }

    /** Removes [entry], unless a newer command for the same control has replaced it. */
    fun clear(vin: String, entry: Entry) = entries.update { map ->
        val key = key(vin, entry.action.control)
        if (map[key] == entry) map - key else map
    }

    fun forControl(vin: String, control: String): Entry? = entries.value[key(vin, control)]

    /** The car's newest line, for a widget, which has room for one. */
    fun latest(map: Map<String, Entry>, vin: String): Entry? =
        map.filterKeys { it.startsWith("$vin:") }.values.maxByOrNull { it.at }
}
