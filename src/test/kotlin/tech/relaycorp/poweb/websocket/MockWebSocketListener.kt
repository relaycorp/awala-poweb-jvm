package tech.relaycorp.poweb.websocket

import io.ktor.http.cio.websocket.CloseReason
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import kotlin.test.assertFalse

class MockWebSocketListener(
    private val actions: MutableList<MockWebSocketAction>
) : WebSocketListener() {
    var connected = false

    val receivedMessages = mutableListOf<ByteArray>()

    internal var closingCode: CloseReason.Codes? = null
    internal var closingReason: String? = null

    override fun onOpen(webSocket: WebSocket, response: Response) {
        assertFalse(connected, "Listener cannot be reused")
        connected = true

        runNextAction(webSocket)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        receivedMessages.add(bytes.toByteArray())

        runNextAction(webSocket)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        receivedMessages.add(text.toByteArray())

        runNextAction(webSocket)
    }

    private fun runNextAction(webSocket: WebSocket) {
        val action = actions.removeFirst()
        action.run(webSocket)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        closingCode = CloseReason.Codes.byCode(code.toShort())
        closingReason = reason
    }
}
