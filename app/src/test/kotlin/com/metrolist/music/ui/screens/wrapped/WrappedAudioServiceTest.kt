package com.metrolist.music.ui.screens.wrapped

import android.app.Application
import com.metrolist.music.R
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WrappedAudioServiceTest {
    @Test
    fun `session cancellation propagates instead of selecting the fallback theme`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val service = WrappedAudioService(context) { _, _, _ -> throw CancellationException("session changed") }
        var cancelled = false
        try {
            service.getSongUri("song")
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test
    fun `superseded resolution is cancelled without returning any media`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<String>()
        val service = WrappedAudioService(RuntimeEnvironment.getApplication()) { _, _, _ ->
            started.complete(Unit)
            response.await()
        }
        var mediaReturned = false
        val request = async {
            service.getSongUri("song")
            mediaReturned = true
        }
        withTimeout(5_000) { started.await() }
        request.cancelAndJoin()
        assertTrue(request.isCancelled)
        assertEquals(false, mediaReturned)
    }

    @Test
    fun `ordinary resolution failures and absent tracks retain the bundled fallback`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val fallback = "android.resource://${context.packageName}/${R.raw.wrapped_theme}"
        val service = WrappedAudioService(context) { _, _, _ -> throw IOException("offline") }
        assertEquals(fallback, service.getSongUri("song").toString())
        assertEquals(fallback, service.getSongUri(null).toString())
    }

    @Test
    fun `successful resolution retains the requested audio URI`() = runBlocking {
        val expected = "https://example.com/audio"
        val service = WrappedAudioService(RuntimeEnvironment.getApplication()) { _, _, _ -> expected }
        assertEquals(expected, service.getSongUri("song").toString())
    }
}
