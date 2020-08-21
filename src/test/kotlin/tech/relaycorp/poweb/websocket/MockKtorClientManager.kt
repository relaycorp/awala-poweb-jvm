package tech.relaycorp.poweb.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.request.HttpRequestData
import io.ktor.util.KtorExperimentalAPI
import kotlin.test.junit5.JUnit5Asserter.fail

/**
 * Workaround to use Ktor's MockEngine with a WebSocket connection, which is currently unsupported.
 *
 * We're not actually implementing a mock WebSocket server with this: We're just recording the
 * requests made.
 */
@KtorExperimentalAPI
class MockKtorClientManager {
    private val requests = mutableListOf<HttpRequestData>()

    val ktorClient = mockClient(requests)

    val request: HttpRequestData
        get() = requests.single()

    suspend fun useClient(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: SkipHandlerException) {
            return
        }
        fail("Mock handler was not reached")
    }

    companion object {
        private fun mockClient(requests: MutableList<HttpRequestData>) = HttpClient(MockEngine) {
            install(WebSockets)

            engine {
                addHandler { request ->
                    requests.add(request)
                    throw SkipHandlerException()
                }
            }
        }

        private class SkipHandlerException : Exception()
    }
}
