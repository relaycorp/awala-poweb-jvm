package tech.relaycorp.poweb

public enum class StreamingMode(internal val headerValue: String) {
    KeepAlive("keep-alive"),
    CloseUponCompletion("close-upon-completion");

    internal companion object {
        const val HEADER_NAME = "X-Relaynet-Streaming-Mode"
    }
}
