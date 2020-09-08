package tech.relaycorp.poweb

import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
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
        @Test
        fun `Request should be made with HTTP POST`() = runBlockingTest {
            var method: HttpMethod? = null
            val client = makeTestClient { request: HttpRequestData ->
                method = request.method
                respondOk()
            }

            client.use { client.preRegisterNode(publicKey) }

            assertEquals(HttpMethod.Post, method)
        }

        @Test
        fun `Request should be made to the appropriate endpoint`() = runBlockingTest {
            var endpointURL: String? = null
            val client = makeTestClient { request: HttpRequestData ->
                endpointURL = request.url.toString()
                respondOk()
            }

            client.use { client.preRegisterNode(publicKey) }

            assertEquals("${client.baseURL}/pre-registrations", endpointURL)
        }

        @Test
        fun `Request Content-Type should be plain text`() = runBlockingTest {
            var contentType: ContentType? = null
            val client = makeTestClient { request: HttpRequestData ->
                contentType = request.body.contentType
                respondOk()
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
                respondOk()
            }

            client.use { client.preRegisterNode(publicKey) }

            assertEquals(
                getSHA256DigestHex(publicKey.encoded),
                requestBody!!.toString(Charset.defaultCharset())
            )
        }

        @Test
        @Disabled
        fun `An invalid response content type should be refused`() {
        }

        @Test
        @Disabled
        fun `20X response status other than 200 should throw an error`() {
        }

        @Test
        @Disabled
        fun `Authorization should be output serialized if status is 200`() {
        }
    }
}
