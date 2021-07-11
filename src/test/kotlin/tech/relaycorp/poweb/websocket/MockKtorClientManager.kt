package tech.relaycorp.poweb.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.features.websocket.WebSockets
import okhttp3.OkHttpClient

/**
 * Workaround to use Ktor's MockEngine with a WebSocket connection, which is currently unsupported.
 *
 * We're not actually implementing a mock WebSocket server with this: We're just recording the
 * requests made.
 */
class MockKtorClientManager {
    val wsClient = mockWSClient()

    companion object {
        private val okhttpEngine: HttpClientEngine = OkHttp.create {
            preconfigured = OkHttpClient.Builder().build()
        }

        private fun mockWSClient() = HttpClient(okhttpEngine) {
            install(WebSockets)
        }
    }
}
