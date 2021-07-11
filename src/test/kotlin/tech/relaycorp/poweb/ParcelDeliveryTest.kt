package tech.relaycorp.poweb

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runBlockingTest
import org.bouncycastle.util.encoders.Base64
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tech.relaycorp.relaynet.bindings.ContentTypes
import tech.relaycorp.relaynet.bindings.pdc.ClientBindingException
import tech.relaycorp.relaynet.bindings.pdc.DetachedSignatureType
import tech.relaycorp.relaynet.bindings.pdc.RejectedParcelException
import tech.relaycorp.relaynet.bindings.pdc.Signer
import tech.relaycorp.relaynet.testing.pki.KeyPairSet
import tech.relaycorp.relaynet.testing.pki.PDACertPath
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class ParcelDeliveryTest {
    private val parcelSerialized = "Let's say I'm the serialization of a parcel".toByteArray()
    private val signer =
        Signer(PDACertPath.PRIVATE_ENDPOINT, KeyPairSet.PRIVATE_ENDPOINT.private)

    @Test
    fun `Request should be made with HTTP POST`() = runBlockingTest {
        var method: HttpMethod? = null
        val client = makeTestClient { request: HttpRequestData ->
            method = request.method
            respondOk()
        }

        client.use { client.deliverParcel(parcelSerialized, signer) }

        assertEquals(HttpMethod.Post, method)
    }

    @Test
    fun `Endpoint should be the one for parcels`() = runBlockingTest {
        var endpointURL: String? = null
        val client = makeTestClient { request: HttpRequestData ->
            endpointURL = request.url.toString()
            respondOk()
        }

        client.use { client.deliverParcel(parcelSerialized, signer) }

        assertEquals("${client.baseHttpUrl}/parcels", endpointURL)
    }

    @Test
    fun `Request content type should be the appropriate value`() = runBlockingTest {
        var contentType: String? = null
        val client = makeTestClient { request: HttpRequestData ->
            contentType = request.body.contentType.toString()
            respondOk()
        }

        client.use { client.deliverParcel(parcelSerialized, signer) }

        assertEquals(ContentTypes.PARCEL.value, contentType)
    }

    @Test
    fun `Request body should be the parcel serialized`() = runBlockingTest {
        var requestBody: ByteArray? = null
        val client = makeTestClient { request: HttpRequestData ->
            assertTrue(request.body is OutgoingContent.ByteArrayContent)
            requestBody = (request.body as OutgoingContent.ByteArrayContent).bytes()
            respondOk()
        }

        client.use { client.deliverParcel(parcelSerialized, signer) }

        assertEquals(parcelSerialized.asList(), requestBody?.asList())
    }

    @Test
    fun `Delivery signature should be in the request headers`() = runBlockingTest {
        var authorizationHeader: String? = null
        val client = makeTestClient { request: HttpRequestData ->
            authorizationHeader = request.headers["Authorization"]
            respondOk()
        }

        client.use { client.deliverParcel(parcelSerialized, signer) }

        assertNotNull(authorizationHeader)
        assertTrue(authorizationHeader!!.startsWith("Relaynet-Countersignature "))
        val countersignatureBase64 = authorizationHeader!!.split(" ")[1]
        val countersignature = Base64.decode(countersignatureBase64)
        DetachedSignatureType.PARCEL_DELIVERY.verify(
            countersignature,
            parcelSerialized,
            listOf(PDACertPath.PRIVATE_GW)
        )
    }

    @Test
    fun `HTTP 20X should be regarded a successful delivery`() = runBlockingTest {
        val client = makeTestClient { respond("", HttpStatusCode.Accepted) }

        client.use { client.deliverParcel(parcelSerialized, signer) }
    }

    @Test
    fun `HTTP 422 should throw a RejectedParcelException`() {
        val client = makeTestClient { respondError(HttpStatusCode.UnprocessableEntity) }

        client.use {
            val exception = assertThrows<RejectedParcelException> {
                runBlockingTest { client.deliverParcel(parcelSerialized, signer) }
            }

            assertEquals("The server rejected the parcel", exception.message)
        }
    }

    @Test
    fun `Other client exceptions should be propagated`() {
        val status = HttpStatusCode.BadRequest
        val client = makeTestClient { respondError(status) }

        client.use {
            val exception = assertThrows<ClientBindingException> {
                runBlockingTest { client.deliverParcel(parcelSerialized, signer) }
            }

            assertEquals("The server returned a $status response", exception.message)
        }
    }
}
