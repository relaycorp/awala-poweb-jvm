package tech.relaycorp.poweb

import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tech.relaycorp.relaynet.wrappers.generateRSAKeyPair
import java.nio.charset.Charset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
@KtorExperimentalAPI
class RegistrationTest {
    private val publicKey = generateRSAKeyPair().public

    @Nested
    inner class PreRegistration {
        private val responseHeaders =
            headersOf("Content-Type", PoWebClient.PNRA_CONTENT_TYPE.toString())

        @Test
        fun `Request should be made with HTTP POST`() = runBlockingTest {
            var method: HttpMethod? = null
            val client = makeTestClient { request: HttpRequestData ->
                method = request.method
                respond(byteArrayOf(), headers = responseHeaders)
            }

            client.use { client.preRegisterNode(publicKey) }

            assertEquals(HttpMethod.Post, method)
        }

        @Test
        fun `Request should be made to the appropriate endpoint`() = runBlockingTest {
            var endpointURL: String? = null
            val client = makeTestClient { request: HttpRequestData ->
                endpointURL = request.url.toString()
                respond(byteArrayOf(), headers = responseHeaders)
            }

            client.use { client.preRegisterNode(publicKey) }

            assertEquals("${client.baseURL}/pre-registrations", endpointURL)
        }

        @Test
        fun `Request Content-Type should be plain text`() = runBlockingTest {
            var contentType: ContentType? = null
            val client = makeTestClient { request: HttpRequestData ->
                contentType = request.body.contentType
                respond(byteArrayOf(), headers = responseHeaders)
            }

            client.use { client.preRegisterNode(publicKey) }

            assertEquals(ContentType.Text.Plain, contentType)
        }

        @Test
        fun `Request body should be SHA-256 digest of the node public key`() = runBlockingTest {
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
                runBlockingTest {
                    client.use { client.preRegisterNode(publicKey) }
                }
            }

            assertEquals(
                "The server returned an invalid Content-Type ($invalidContentType)",
                exception.message
            )
        }

        @Test
        fun `Authorization should be output serialized if request succeeds`() {
            runBlockingTest {
                val authorizationSerialized = "This is the PNRA".toByteArray()
                val client = makeTestClient {
                    respond(authorizationSerialized, headers = responseHeaders)
                }

                client.use {
                    assertEquals(
                        authorizationSerialized.asList(),
                        it.preRegisterNode(publicKey).asList()
                    )
                }
            }
        }
    }
}
