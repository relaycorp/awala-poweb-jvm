package tech.relaycorp.poweb

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.util.KtorExperimentalAPI
import java.security.MessageDigest

internal const val NON_ROUTABLE_IP_ADDRESS = "192.0.2.1"

@KtorExperimentalAPI
internal fun makeTestClient(handler: MockRequestHandler): PoWebClient {
    val poWebClient = PoWebClient.initLocal()
    poWebClient.ktorClient = HttpClient(MockEngine) {
        engine {
            addHandler(handler)
        }
    }
    return poWebClient
}

internal fun getSHA256DigestHex(plaintext: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(plaintext).joinToString("") { "%02x".format(it) }
}
