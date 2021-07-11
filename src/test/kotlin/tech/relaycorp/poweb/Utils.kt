package tech.relaycorp.poweb

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import java.security.MessageDigest

internal fun makeTestClient(handler: MockRequestHandler): PoWebClient {
    val ktorEngine = MockEngine.create {
        addHandler(handler)
    }
    return PoWebClient("127.0.0.1", 1234, false, ktorEngine)
}

internal fun getSHA256DigestHex(plaintext: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(plaintext).joinToString("") { "%02x".format(it) }
}
