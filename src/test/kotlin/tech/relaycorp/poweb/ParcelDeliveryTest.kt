package tech.relaycorp.poweb

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.ConnectException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
@KtorExperimentalAPI
class ParcelDeliveryTest {
    private val parcelSerialized = "Let's say I'm the serialization of a parcel".toByteArray()

    @Test
    fun `Request should be made with HTTP POST`() = runBlockingTest {
        var method: HttpMethod? = null
        val client = makeTestClient { request: HttpRequestData ->
            method = request.method
            respondOk()
        }

        client.use { client.deliverParcel(parcelSerialized) }

        assertEquals(HttpMethod.Post, method)
    }

    @Test
    fun `Endpoint should be the one for parcels`() = runBlockingTest {
        var endpointURL: String? = null
        val client = makeTestClient { request: HttpRequestData ->
            endpointURL = request.url.toString()
            respondOk()
        }

        client.use { client.deliverParcel(parcelSerialized) }

        assertEquals("${client.baseURL}/parcels", endpointURL)
    }

    @Test
    fun `Request content type should be the appropriate value`() = runBlockingTest {
        var contentType: String? = null
        val client = makeTestClient { request: HttpRequestData ->
            contentType = request.body.contentType.toString()
            respondOk()
        }

        client.use { client.deliverParcel(parcelSerialized) }

        assertEquals("application/vnd.relaynet.parcel", contentType)
    }

    @Test
    fun `Request body should be the parcel serialized`() = runBlockingTest {
        var requestBody: ByteArray? = null
        val client = makeTestClient { request: HttpRequestData ->
            assertTrue(request.body is OutgoingContent.ByteArrayContent)
            requestBody = (request.body as OutgoingContent.ByteArrayContent).bytes()
            respondOk()
        }

        client.use { client.deliverParcel(parcelSerialized) }

        assertEquals(parcelSerialized.asList(), requestBody?.asList())
    }

    @Test
    fun `HTTP 20X should be regarded a successful delivery`() = runBlockingTest {
        val client = makeTestClient { respond("", HttpStatusCode.Accepted) }

        client.use { client.deliverParcel(parcelSerialized) }
    }

    @Test
    fun `HTTP 30X responses should be regarded protocol violations by the server`() {
        val client = makeTestClient { respond("", HttpStatusCode.Found) }

        client.use {
            val exception = assertThrows<ServerBindingException> {
                runBlockingTest { client.deliverParcel(parcelSerialized) }
            }

            assertEquals(
                "Received unexpected status (${HttpStatusCode.Found})",
                exception.message
            )
        }
    }

    @Test
    fun `HTTP 403 should throw a RefusedParcelException`() {
        val client = makeTestClient { respondError(HttpStatusCode.Forbidden) }

        client.use {
            val exception = assertThrows<RefusedParcelException> {
                runBlockingTest { client.deliverParcel(parcelSerialized) }
            }

            assertEquals(
                "Parcel was refused by the server (${HttpStatusCode.Forbidden})",
                exception.message
            )
        }
    }

    @Test
    fun `Other 40X responses should be regarded protocol violations by the client`() {
        val client = makeTestClient { respondError(HttpStatusCode.BadRequest) }

        client.use {
            val exception = assertThrows<ClientBindingException> {
                runBlockingTest { client.deliverParcel(parcelSerialized) }
            }

            assertEquals(
                "The server reports that the client violated binding " +
                    "(${HttpStatusCode.BadRequest})",
                exception.message
            )
        }
    }

    @Test
    fun `HTTP 50X responses should throw a ServerConnectionException`() {
        val client = makeTestClient { respondError(HttpStatusCode.BadGateway) }

        client.use {
            val exception = assertThrows<ServerConnectionException> {
                runBlockingTest { client.deliverParcel(parcelSerialized) }
            }

            assertEquals(
                "The server was unable to fulfil the request (${HttpStatusCode.BadGateway})",
                exception.message
            )
        }
    }

    @Test
    fun `TCP connection issues should throw a ServerConnectionException`() {
        val nonRouteableIPAddress = "192.0.2.1"
        // Use a real client to try to open an actual network connection
        val client = PoWebClient.initRemote(nonRouteableIPAddress)

        client.use {
            val exception = assertThrows<ServerConnectionException> {
                runBlocking { client.deliverParcel(parcelSerialized) }
            }

            assertEquals("Failed to connect to ${client.baseURL}", exception.message)
            assertTrue(exception.cause is ConnectException)
        }
    }
}
