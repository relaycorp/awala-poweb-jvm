package tech.relaycorp.poweb

import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tech.relaycorp.relaynet.bindings.ContentTypes
import tech.relaycorp.relaynet.bindings.pdc.ClientBindingException
import tech.relaycorp.relaynet.bindings.pdc.ServerBindingException
import tech.relaycorp.relaynet.messages.InvalidMessageException
import tech.relaycorp.relaynet.messages.control.PrivateNodeRegistration
import tech.relaycorp.relaynet.testing.pki.KeyPairSet
import tech.relaycorp.relaynet.testing.pki.PDACertPath
import java.nio.charset.Charset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Suppress("RedundantInnerClassModifier")
class RegistrationTest {
    @Nested
    inner class PreRegistration {
        private val publicKey = KeyPairSet.PRIVATE_GW.public
        private val responseHeaders =
            headersOf("Content-Type", ContentTypes.NODE_REGISTRATION_AUTHORIZATION.value)

        @Test
        fun `Request method should be POST`() = runBlocking {
            var method: HttpMethod? = null
            val client = makeTestClient { request: HttpRequestData ->
                method = request.method
                respond(byteArrayOf(), headers = responseHeaders)
            }

            client.use { client.preRegisterNode(publicKey) }

            assertEquals(HttpMethod.Post, method)
        }

        @Test
        fun `Request should be made to the appropriate endpoint`() = runBlocking {
            var endpointURL: String? = null
            val client = makeTestClient { request: HttpRequestData ->
                endpointURL = request.url.toString()
                respond(byteArrayOf(), headers = responseHeaders)
            }

            client.use { client.preRegisterNode(publicKey) }

            assertEquals("${client.baseHttpUrl}/pre-registrations", endpointURL)
        }

        @Test
        fun `Request Content-Type should be plain text`() = runBlocking {
            var contentType: ContentType? = null
            val client = makeTestClient { request: HttpRequestData ->
                contentType = request.body.contentType
                respond(byteArrayOf(), headers = responseHeaders)
            }

            client.use { client.preRegisterNode(publicKey) }

            assertEquals(ContentTypes.NODE_PRE_REGISTRATION.value, contentType.toString())
        }

        @Test
        fun `Request body should be SHA-256 digest of the node public key`() = runBlocking {
            var requestBody: ByteArray? = null
            val client = makeTestClient { request: HttpRequestData ->
                assertTrue(request.body is OutgoingContent.ByteArrayContent)
                requestBody = (request.body as OutgoingContent.ByteArrayContent).bytes()
                respond(byteArrayOf(), headers = responseHeaders)
            }

            client.use { client.preRegisterNode(publicKey) }

            assertEquals(
                getSHA256DigestHex(publicKey.encoded),
                requestBody!!.toString(Charset.defaultCharset())
            )
        }

        @Test
        fun `An invalid response Content-Type should be refused`() {
            val invalidContentType = ContentType.Application.Json
            val client = makeTestClient {
                respond(
                    "{}",
                    headers = headersOf("Content-Type", invalidContentType.toString())
                )
            }

            val exception = assertThrows<ServerBindingException> {
                runBlocking {
                    client.use { client.preRegisterNode(publicKey) }
                }
            }

            assertEquals(
                "The server returned an invalid Content-Type ($invalidContentType)",
                exception.message
            )
        }

        @Test
        fun `Exception should be thrown if server reports we violated binding`() {
            val status = HttpStatusCode.Forbidden
            val client = makeTestClient {
                respond("{}", status = status)
            }

            val exception = assertThrows<ClientBindingException> {
                runBlocking {
                    client.use { client.preRegisterNode(publicKey) }
                }
            }

            assertEquals(
                "The server returned a $status response",
                exception.message
            )
        }

        @Test
        fun `Registration request should be output if pre-registration succeeds`() =
            runBlocking {
                val authorizationSerialized = "This is the PNRA".toByteArray()
                val client = makeTestClient {
                    respond(authorizationSerialized, headers = responseHeaders)
                }

                client.use {
                    val registrationRequest = it.preRegisterNode(publicKey)
                    assertEquals(publicKey, registrationRequest.privateNodePublicKey)
                    assertEquals(
                        authorizationSerialized.asList(),
                        registrationRequest.pnraSerialized.asList()
                    )
                }
            }
    }

    @Nested
    inner class Registration {
        private val pnrrSerialized = "The PNRR".toByteArray()
        private val responseHeaders =
            headersOf("Content-Type", ContentTypes.NODE_REGISTRATION.value)

        private val registration =
            PrivateNodeRegistration(PDACertPath.PRIVATE_GW, PDACertPath.INTERNET_GW, "example.org")
        private val registrationSerialized = registration.serialize()

        @Test
        fun `Request method should be POST`() = runBlocking {
            var method: HttpMethod? = null
            val client = makeTestClient { request: HttpRequestData ->
                method = request.method
                respond(registrationSerialized, headers = responseHeaders)
            }

            client.use { client.registerNode(pnrrSerialized) }

            assertEquals(HttpMethod.Post, method)
        }

        @Test
        fun `Request should be made to the appropriate endpoint`() = runBlocking {
            var endpointURL: String? = null
            val client = makeTestClient { request: HttpRequestData ->
                endpointURL = request.url.toString()
                respond(registrationSerialized, headers = responseHeaders)
            }

            client.use { client.registerNode(pnrrSerialized) }

            assertEquals("${client.baseHttpUrl}/nodes", endpointURL)
        }

        @Test
        fun `Request Content-Type should be a PNRR`() = runBlocking {
            var contentType: ContentType? = null
            val client = makeTestClient { request: HttpRequestData ->
                contentType = request.body.contentType
                respond(registrationSerialized, headers = responseHeaders)
            }

            client.use { client.registerNode(pnrrSerialized) }

            assertEquals(ContentTypes.NODE_REGISTRATION_REQUEST.value, contentType.toString())
        }

        @Test
        fun `Request body should be the PNRR serialized`() = runBlocking {
            var requestBody: ByteArray? = null
            val client = makeTestClient { request: HttpRequestData ->
                assertTrue(request.body is OutgoingContent.ByteArrayContent)
                requestBody = (request.body as OutgoingContent.ByteArrayContent).bytes()
                respond(registrationSerialized, headers = responseHeaders)
            }

            client.use { client.registerNode(pnrrSerialized) }

            assertEquals(pnrrSerialized.asList(), requestBody!!.asList())
        }

        @Test
        fun `An invalid response Content-Type should be refused`() {
            val invalidContentType = ContentType.Application.Json
            val client = makeTestClient {
                respond(
                    "{}",
                    headers = headersOf("Content-Type", invalidContentType.toString())
                )
            }

            val exception = assertThrows<ServerBindingException> {
                runBlocking {
                    client.use { client.registerNode(pnrrSerialized) }
                }
            }

            assertEquals(
                "The server returned an invalid Content-Type ($invalidContentType)",
                exception.message
            )
        }

        @Test
        fun `An invalid registration should be refused`() {
            val client = makeTestClient {
                respond("{}", headers = responseHeaders)
            }

            val exception = assertThrows<ServerBindingException> {
                runBlocking {
                    client.use { client.registerNode(pnrrSerialized) }
                }
            }

            assertEquals("The server returned a malformed registration", exception.message)
            assertTrue(exception.cause is InvalidMessageException)
        }

        @Test
        fun `Exception should be thrown if server reports we violated binding`() = runBlocking {
            val client = makeTestClient {
                respond("{}", status = HttpStatusCode.Forbidden)
            }

            val exception = assertThrows<ClientBindingException> {
                runBlocking {
                    client.use { client.registerNode(pnrrSerialized) }
                }
            }

            assertEquals(
                "The server returned a ${HttpStatusCode.Forbidden} response",
                exception.message
            )
        }

        @Test
        fun `Registration should be output if request succeeds`() = runBlocking {
            val client = makeTestClient {
                respond(registrationSerialized, headers = responseHeaders)
            }

            client.use {
                val finalRegistration = it.registerNode(pnrrSerialized)
                assertEquals(
                    registration.privateNodeCertificate,
                    finalRegistration.privateNodeCertificate
                )
                assertEquals(
                    registration.gatewayCertificate,
                    finalRegistration.gatewayCertificate
                )
            }
        }
    }
}
