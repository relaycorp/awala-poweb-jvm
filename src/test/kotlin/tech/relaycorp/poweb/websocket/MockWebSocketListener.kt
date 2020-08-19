package tech.relaycorp.poweb.websocket

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

class MockWebSocketListener(
    private val actions: MutableList<MockWebSocketAction>
) : WebSocketListener() {
    val receivedMessages = mutableListOf<ByteArray>()

    internal var closingCode: Int? = null
    private var closingReason: String? = null

    override fun onOpen(webSocket: WebSocket, response: Response) {
        runNextAction(webSocket)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        receivedMessages.add(bytes.toByteArray())
        runNextAction(webSocket)
    }

    private fun runNextAction(webSocket: WebSocket) {
        val action = actions.removeFirst()
        action.run(webSocket)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        closingCode = code
        closingReason = reason
    }
}
