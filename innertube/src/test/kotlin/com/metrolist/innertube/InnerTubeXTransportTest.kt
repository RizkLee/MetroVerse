package com.metrolist.innertube

import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

class InnerTubeXTransportTest {
    private fun InnerTubeXClient(handler: io.ktor.client.engine.mock.MockRequestHandler): HttpClient =
        HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false })
            }
        }

    private fun InnerTubeXTransport.session(
        visitor: String? = "visitor-a",
        account: String = "1",
        proxy: Proxy? = null,
        proxyAuth: String? = null,
    ) = configure(
        YouTubeLocale("US", "en"), null, visitor, "channel-a", account, true, proxy, proxyAuth,
    )

    @Test
    fun `unchanged settings preserve session generation and transport`() {
        val bridge = InnerTubeXTransport { _, _ -> InnerTubeXClient { respond("{}") } }
        val first = bridge.session()
        val key = first.sessionKey
        try {
            val second = bridge.session()
            assertSame(first, second)
            assertEquals(key, second.sessionKey)
            assertEquals("1", second.innerTube.sessionSnapshot().authUser)
            assertEquals("channel-a", second.innerTube.sessionSnapshot().dataSyncId)
        } finally {
            first.innerTube.close()
            first.httpClient.close()
        }
    }

    @Test
    fun `library acquired visitor data survives an unchanged missing preference`() {
        val bridge = InnerTubeXTransport { _, _ -> InnerTubeXClient { respond("{}") } }
        val transport = bridge.session(visitor = null)
        val originalKey = transport.sessionKey
        try {
            transport.innerTube.visitorData = "fresh-visitor"
            val next = bridge.session(visitor = null)
            assertEquals("fresh-visitor", next.innerTube.visitorData)
            assertEquals(originalKey, next.sessionKey)
            bridge.session(visitor = "user-updated-visitor")
            assertTrue(originalKey != next.sessionKey)
        } finally {
            transport.innerTube.close()
            transport.httpClient.close()
        }
    }

    @Test
    fun `proxy or proxy authentication changes rebuild transport and retain identity`() {
        var created = 0
        val bridge = InnerTubeXTransport { _, _ ->
            created++
            HttpClient(MockEngine { respond("{}") })
        }
        val first = bridge.session()
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 8080))
        val second = bridge.session(proxy = proxy)
        val third = bridge.session(proxy = proxy, proxyAuth = "test-proxy-auth")
        try {
            assertNotSame(first.httpClient, second.httpClient)
            assertNotSame(second.httpClient, third.httpClient)
            assertEquals(3, created)
            assertTrue(third.generation > second.generation)
            assertEquals("visitor-a", third.innerTube.sessionSnapshot().visitorData)
            assertEquals("1", third.innerTube.sessionSnapshot().authUser)
        } finally {
            third.innerTube.close()
            third.httpClient.close()
        }
    }

    @Test(timeout = 10_000)
    fun `account replacement cancels an old authenticated playback request`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val bridge = InnerTubeXTransport { _, _ ->
            InnerTubeXClient {
                started.complete(Unit)
                releaseResponse.await()
                respond("{}")
            }
        }
        val snapshot = bridge.configure(
            YouTubeLocale("US", "en"), "SAPISID=test-session", "visitor-a", "channel-a", "1", true, null, null,
        )
        val oldKey = snapshot.sessionKey
        val request = async {
            snapshot.innerTube.playerWithSessionBound(
                client = YouTubeClient.WEB_REMIX,
                videoId = "video-id",
                playlistId = null,
                signatureTimestamp = null,
                poToken = null,
                requestVisitorData = "visitor-a",
                requestSession = snapshot.innerTube.sessionSnapshot(),
            ).bodyAsText()
        }
        try {
            withTimeout(5_000) { started.await() }
            bridge.session(visitor = "visitor-b", account = "2")
            var cancelled = false
            try {
                withTimeout(5_000) { request.await() }
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
            assertTrue(request.isCancelled)
            assertTrue(snapshot.sessionKey != oldKey)
            assertEquals("2", snapshot.innerTube.sessionSnapshot().authUser)
            assertEquals(null, snapshot.innerTube.sessionSnapshot().cookie)
        } finally {
            releaseResponse.complete(Unit)
            request.cancelAndJoin()
            snapshot.innerTube.close()
            snapshot.httpClient.close()
        }
    }

    @Test
    fun `account index normalization rejects malformed identity`() {
        assertEquals("2", normalizeYouTubeAuthUser("2"))
        assertEquals("0", normalizeYouTubeAuthUser(null))
        assertEquals("0", normalizeYouTubeAuthUser("account2"))
        assertEquals("0", normalizeYouTubeAuthUser("-1"))
        assertEquals("0", normalizeYouTubeAuthUser(""))
    }
}
