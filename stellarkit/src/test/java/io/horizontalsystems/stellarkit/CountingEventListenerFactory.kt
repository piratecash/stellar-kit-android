package io.horizontalsystems.stellarkit

import okhttp3.Call
import okhttp3.EventListener
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test double for an OkHttp [EventListener.Factory]. Its identity is asserted to be
 * forwarded onto the Stellar SDK clients; the create count is available for callers
 * that want to assert it is actually invoked on real calls.
 */
class CountingEventListenerFactory : EventListener.Factory {
    val createCount = AtomicInteger(0)

    override fun create(call: Call): EventListener {
        createCount.incrementAndGet()
        return EventListener.NONE
    }
}
