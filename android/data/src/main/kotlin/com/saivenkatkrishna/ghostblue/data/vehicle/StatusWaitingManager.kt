package com.saivenkatkrishna.ghostblue.data.vehicle

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Interruptible sleeps between command-verification polls. A status that
 * arrives for the car ends the sleeps that began before it; ordering is by
 * ticket rather than clock, so a poll's own status can't cut its next
 * sleep short (each poll is a real-time read that wakes the car).
 */
class StatusWaitingManager {
    private class Waiter(val vehicleId: UUID, val ticket: Long, val signal: CompletableDeferred<Unit>)

    private val mutex = Mutex()
    private val tickets = AtomicLong()
    private val waiters = mutableMapOf<Long, Waiter>()

    /** A point in the ordering; pass it to [wakeUp] to end only sleeps that began before it. */
    fun ticket(): Long = tickets.incrementAndGet()

    /** Sleeps [seconds], or until woken. */
    suspend fun wait(vehicleId: UUID, seconds: Int) {
        val waiter = Waiter(vehicleId, ticket(), CompletableDeferred())
        mutex.withLock { waiters[waiter.ticket] = waiter }
        try {
            withTimeoutOrNull(max(0, seconds) * 1000L) { waiter.signal.await() }
        } finally {
            withContext(NonCancellable) { mutex.withLock { waiters.remove(waiter.ticket) } }
        }
    }

    /** Ends the car's sleeps that began before [issuedAt]; true when one ended. */
    suspend fun wakeUp(vehicleId: UUID, issuedAt: Long = ticket()): Boolean {
        val woken = mutex.withLock {
            waiters.values.filter { it.vehicleId == vehicleId && it.ticket < issuedAt }
                .onEach { waiters.remove(it.ticket) }
        }
        woken.forEach { it.signal.complete(Unit) }
        return woken.isNotEmpty()
    }

    suspend fun clearWakeUp(vehicleId: UUID) {
        wakeUp(vehicleId)
    }

    companion object {
        val shared = StatusWaitingManager()
    }
}
