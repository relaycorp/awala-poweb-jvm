package tech.relaycorp.poweb.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.features.websocket.WebSockets
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.IOException
import kotlin.test.assertTrue

open class WebSocketTestCase(private val autoStartServer: Boolean = true) {
    protected val mockWebServer = MockWebServer()

    protected var listener: MockWebSocketListener? = null

    private val okhttpEngine: HttpClientEngine = OkHttp.create {
        preconfigured = OkHttpClient.Builder().build()
    }
    protected val ktorWSClient = HttpClient(okhttpEngine) {
        install(WebSockets)
    }

    @BeforeEach
    fun startServer() {
        if (autoStartServer) {
            mockWebServer.start()
        }

        listener = null
    }

    @AfterEach
    fun stopServer() {
        try {
            mockWebServer.shutdown()
        } catch (exc: IOException) {
            // Ignore the weird "Gave up waiting for queue to shut down" exception in
            // MockWebServer when the code under test closes the connection explicitly
            // TODO: Raise issue in OkHTTP repo
        }
    }

    protected fun setListenerActions(vararg actions: MockWebSocketAction) {
        listener = MockWebSocketListener(actions.toMutableList(), mockWebServer)
        mockWebServer.enqueue(MockResponse().withWebSocketUpgrade(listener!!))
    }

    /**
     * Wait until the connection to the server has been closed.
     */
    protected fun awaitForConnectionClosure() {
        assertTrue(listener!!.connected, "The server must've got at least one connection")
        await().until { !listener!!.connectionOpen }
    }
}
