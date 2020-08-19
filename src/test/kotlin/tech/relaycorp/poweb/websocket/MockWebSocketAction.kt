package tech.relaycorp.poweb.websocket

import okhttp3.WebSocket
import okio.ByteString.Companion.toByteString

sealed class MockWebSocketAction {
    abstract fun run(webSocket: WebSocket)
}

class SendBinaryMessageAction(private val message: ByteArray) : MockWebSocketAction() {
    override fun run(webSocket: WebSocket) {
        webSocket.send(message.toByteString())
    }
}

class SendTextMessageAction(private val message: String) : MockWebSocketAction() {
    override fun run(webSocket: WebSocket) {
        webSocket.send(message)
    }
}

class CloseConnectionAction(
    private val code: Int,
    private val reason: String? = null
) : MockWebSocketAction() {
    override fun run(webSocket: WebSocket) {
        webSocket.close(code, reason)
    }
}
