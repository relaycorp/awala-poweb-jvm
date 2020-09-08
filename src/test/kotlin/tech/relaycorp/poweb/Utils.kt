package tech.relaycorp.poweb

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.util.KtorExperimentalAPI

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

const val NON_ROUTABLE_IP_ADDRESS = "192.0.2.1"
