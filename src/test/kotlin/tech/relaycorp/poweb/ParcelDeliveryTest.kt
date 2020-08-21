package tech.relaycorp.poweb

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondOk
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
@KtorExperimentalAPI
class ParcelDeliveryTest {
    private val parcelSerialized = "Let's say I'm the serialization of a parcel".toByteArray()

    @Test
    fun `Request should be made with HTTP POST`() = runBlockingTest {
        var method: HttpMethod? = null
        val client = makeClient { request ->
            method = request.method
            respondOk()
        }

        client.use { client.deliverParcel(parcelSerialized) }

        assertEquals(HttpMethod.Post, method)
    }

    @Test
    fun `Endpoint should be the one for parcels`() = runBlockingTest {
        var endpointURL: String? = null
        val client = makeClient { request ->
            endpointURL = request.url.toString()
            respondOk()
        }

        client.use { client.deliverParcel(parcelSerialized) }

        assertEquals("${client.baseURL}/parcels", endpointURL)
    }

    @Test
    fun `Request content type should be the appropriate value`() = runBlockingTest {
        var contentType: String? = null
        val client = makeClient { request ->
            contentType = request.body.contentType.toString()
            respondOk()
        }

        client.use { client.deliverParcel(parcelSerialized) }

        assertEquals("application/vnd.relaynet.parcel", contentType)
    }

    @Test
    fun `Request body should be the parcel serialized`() = runBlockingTest {
        var requestBody: ByteArray? = null
        val client = makeClient { request ->
            assertTrue(request.body is OutgoingContent.ByteArrayContent)
            requestBody = (request.body as OutgoingContent.ByteArrayContent).bytes()
            respondOk()
        }

        client.use { client.deliverParcel(parcelSerialized) }

        assertEquals(parcelSerialized.asList(), requestBody?.asList())
    }

    @Test
    fun `HTTP 20X should be regarded a successful delivery`() = runBlockingTest {
        val client = makeClient { respond("", HttpStatusCode.Accepted) }

        client.use { client.deliverParcel(parcelSerialized) }
    }

    @Test
    @Disabled
    fun `HTTP 30X responses should be regarded protocol violations by the server`() {
    }

    @Test
    @Disabled
    fun `HTTP 403 should throw a RefusedParcelException`() {
    }

    @Test
    @Disabled
    fun `RefusedParcelException should include the reason if present`() {
    }

    @Test
    @Disabled
    fun `Other 40X responses should be regarded protocol violations by the client`() {
    }

    @Test
    @Disabled
    fun `HTTP 50X responses should be regarded transient server errors`() {
    }

    private fun makeClient(handler: MockRequestHandler): PoWebClient {
        val poWebClient = PoWebClient.initLocal()
        poWebClient.ktorClient = HttpClient(MockEngine) {
            engine {
                addHandler(handler)
            }
        }
        return poWebClient
    }
}