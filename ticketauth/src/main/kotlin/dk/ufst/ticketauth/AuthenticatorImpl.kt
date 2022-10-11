package dk.ufst.ticketauth

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

internal class AuthenticatorImpl(
    private var engine: AuthEngine
): Authenticator {
    init {
        engine.onWakeThreads = {
            wakeThreads()
        }
    }

    private var latch: AtomicReference<CountDownLatch> = AtomicReference()

    override fun login() {
        if(latch.compareAndSet(null, CountDownLatch(1))) {
            engine.clear()
            engine.runOnUiThread {
                engine.launchAuthIntent()
            }
        } else {
            log("Login already in progress")
        }
    }

    override fun logout() {
        if(latch.compareAndSet(null, CountDownLatch(1))) {
            engine.runOnUiThread {
                engine.launchLogoutIntent()
                engine.clear()
            }
        } else {
            log("Cannot logout while login or token refresh is in progress")
        }
    }

    override fun prepareCall(): Boolean {
        if(engine.needsTokenRefresh()) {
            log("Token needs refresh, pausing network call")
            // first caller creates the latch and waits, subsequent callers just wait on the latch
            if(latch.compareAndSet(null, CountDownLatch(1))) {
                if(!engine.performBlockingTokenRefresh()) {
                    log("Token could NOT be refreshed, attempting login")
                    engine.runOnUiThread {
                        engine.launchAuthIntent()
                    }
                } else {
                    log("Refresh succeeded, resuming network calls in progress")
                    wakeThreads()
                }
            } else {
                log("Token refresh or login in progress, awaiting completion...")
            }
            // goto sleep until wakeThreads is called
            latch.get()?.await()
            if(engine.needsTokenRefresh()) {
                log("Thread woke up but TicketAuth didn't manage to obtain a new token, return error")
                return false
            }
        }
        return true
    }
    override fun clearToken() = engine.clear()

    private fun wakeThreads() {
        latch.getAndSet(null).countDown()
    }
}