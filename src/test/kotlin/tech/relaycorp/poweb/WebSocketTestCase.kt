package tech.relaycorp.poweb

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import tech.relaycorp.poweb.websocket.MockWebSocketAction
import tech.relaycorp.poweb.websocket.MockWebSocketListener
import java.io.IOException

open class WebSocketTestCase(private val autoStartServer: Boolean = true) {
    protected val mockWebServer = MockWebServer()

    protected var listener: MockWebSocketListener? = null

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
        listener = MockWebSocketListener(actions.toMutableList())
        mockWebServer.enqueue(MockResponse().withWebSocketUpgrade(listener!!))
    }
}
