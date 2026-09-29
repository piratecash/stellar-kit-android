package io.horizontalsystems.stellarkit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Guards the passive-observer invariant of the network-error logging seam: a supplied
 * [okhttp3.EventListener.Factory] must reach BOTH the general and the submit OkHttp
 * clients of the Stellar SDK Server, and supplying none must leave the default clients
 * untouched (behavior identical to before this feature).
 */
class StellarKitForwardingTest {

    @Test
    fun getServer_withEventListenerFactory_attachesToHttpAndSubmitClients() {
        val factory = CountingEventListenerFactory()

        val server = StellarKit.getServer(Network.TestNet, factory)

        assertSame(factory, server.httpClient.eventListenerFactory)
        assertSame(factory, server.submitHttpClient.eventListenerFactory)
    }

    @Test
    fun getServer_withoutFactory_leavesDefaultClients() {
        val server = StellarKit.getServer(Network.TestNet, null)

        assertFalse(server.httpClient.eventListenerFactory is CountingEventListenerFactory)
        assertFalse(server.submitHttpClient.eventListenerFactory is CountingEventListenerFactory)
    }
}
