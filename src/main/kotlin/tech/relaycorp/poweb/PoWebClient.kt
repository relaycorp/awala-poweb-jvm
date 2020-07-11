package tech.relaycorp.poweb

class PoWebClient private constructor(
    internal val hostName: String,
    internal val port: Int,
    internal val useTls: Boolean
) {
    companion object {
        private const val defaultLocalPort = 276
        private const val defaultRemotePort = 443

        fun initLocal(port: Int = defaultLocalPort) =
                PoWebClient("127.0.0.1", port, false)

        fun initRemote(hostName: String, port: Int = defaultRemotePort) =
                PoWebClient(hostName, port, true)
    }
}
