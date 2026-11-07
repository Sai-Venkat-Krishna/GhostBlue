package com.saivenkatkrishna.ghostblue.data.account

import androidx.room.withTransaction
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import java.time.Duration
import java.time.Instant

/**
 * Upkeep of the command log (`remote_commands`, iOS's
 * `RemoteCommandActivity`), which gains a row for every lock, climate and
 * charging command from the app, widgets, tiles, shortcuts and the watch.
 * iOS never trims it; here it keeps a year, and at most [MAX_ROWS], as the
 * HTTP log keeps its newest 100. A command left "sending" by a process
 * that died is closed as failed, so it doesn't spin forever.
 */
object RemoteCommandLog {
    const val MAX_ROWS = 500
    val MAX_AGE: Duration = Duration.ofDays(365)

    /** Longer than any command waits for the car. */
    val UNFINISHED_AFTER: Duration = Duration.ofMinutes(15)

    const val UNFINISHED_MESSAGE = "No result: GhostBlue stopped before the car answered."

    /** Returns how many rows were removed. */
    suspend fun prune(db: GhostBlueDatabase, now: Instant = Instant.now()): Int = db.withTransaction {
        val commands = db.remoteCommands()
        commands.failUnfinished(now.minus(UNFINISHED_AFTER), now, UNFINISHED_MESSAGE)
        commands.deleteBefore(now.minus(MAX_AGE)) + commands.deleteAllButNewest(MAX_ROWS)
    }
}
