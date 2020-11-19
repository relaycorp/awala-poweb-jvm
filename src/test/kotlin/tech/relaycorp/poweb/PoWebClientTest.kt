package tech.relaycorp.poweb

import com.nhaarman.mockitokotlin2.spy
import com.nhaarman.mockitokotlin2.verify
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.engine.okhttp.OkHttpEngine
import io.ktor.client.features.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.util.InternalAPI
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tech.relaycorp.poweb.websocket.CloseConnectionAction
import tech.relaycorp.poweb.websocket.MockKtorClientManager
import tech.relaycorp.poweb.websocket.ServerShutdownAction
import tech.relaycorp.poweb.websocket.WebSocketTestCase
import tech.relaycorp.relaynet.bindings.pdc.ClientBindingException
import tech.relaycorp.relaynet.bindings.pdc.ServerBindingException
import tech.relaycorp.relaynet.bindings.pdc.ServerConnectionException
import java.io.EOFException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketException
import java.net.UnknownHostException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
@KtorExperimentalAPI
@Suppress("RedundantInnerClassModifier")
class PoWebClientTest {
    @Nested
    inner class Constructor {
        @Nested
        inner class InitLocal {
            @Test
            fun `Host name should be the localhost IP address`() {
                val client = PoWebClient.initLocal()

                assertEquals("127.0.0.1", client.hostName)
            }

            @Test
            fun `TLS should not be used`() {
                val client = PoWebClient.initLocal()

                assertFalse(client.useTls)
            }

            @Test
            fun `Port should default to 276`() {
                val client = PoWebClient.initLocal()

                assertEquals(276, client.port)
            }

            @Test
            fun `Port should be overridable`() {
                val customPort = 13276
                val client = PoWebClient.initLocal(customPort)

                assertEquals(customPort, client.port)
            }

            @Test
            fun `Correct HTTP URL should be set when not using TLS`() {
                val client = PoWebClient.initLocal()

                assertEquals("http://127.0.0.1:276/v1", client.baseURL)
            }

            @InternalAPI
            @Test
            fun `OkHTTP should be the client engine`() {
                val client = PoWebClient.initLocal()

                assertTrue(client.ktorClient.engine is OkHttpEngine)
            }
        }

        @Nested
        inner class InitRemote {
            private val hostName = "gb.relaycorp.tech"

            @Test
            fun `Specified host name should be honored`() {
                val client = PoWebClient.initRemote(hostName)

                assertEquals(hostName, client.hostName)
            }

            @Test
            fun `TLS should be used`() {
                val client = PoWebClient.initRemote(hostName)

                assertTrue(client.useTls)
            }

            @Test
            fun `Port should default to 443`() {
                val client = PoWebClient.initRemote(hostName)

                assertEquals(443, client.port)
            }

            @Test
            fun `Port should be overridable`() {
                val customPort = 1234
                val client = PoWebClient.initRemote(hostName, customPort)

                assertEquals(customPort, client.port)
            }

            @Test
            fun `Correct HTTPS URL should be set when using TLS`() {
                val client = PoWebClient.initRemote(hostName)

                assertEquals("https://$hostName:443/v1", client.baseURL)
            }

            @InternalAPI
            @Test
            fun `OkHTTP should be the client engine`() {
                val client = PoWebClient.initRemote(hostName)

                assertTrue(client.ktorClient.engine is OkHttpEngine)
            }
        }
    }

    @InternalAPI
    @Test
    fun `OkHTTP should be the client engine`() {
        val client = PoWebClient.initLocal()

        assertTrue(client.ktorClient.engine is OkHttpEngine)
    }

    @Test
    fun `Close method should close underlying Ktor client`() {
        val client = PoWebClient.initLocal()
        client.ktorClient = spy(client.ktorClient)

        client.close()

        verify(client.ktorClient).close()
    }

    @Nested
    inner class Post {
        private val path = "/foo"
        private val body = ByteArrayContent("bar".toByteArray(), ContentType.Text.Plain)

        @Nested
        inner class Request {
            @Test
            fun `Request should be made with HTTP POST`() = runBlockingTest {
                var method: HttpMethod? = null
                val client = makeTestClient { request: HttpRequestData ->
                    method = request.method
                    respondOk()
                }

                client.use { client.post(path, body) }

                assertEquals(HttpMethod.Post, method)
            }

            @Test
            fun `Specified path should be honored`() = runBlockingTest {
                var endpointURL: String? = null
                val client = makeTestClient { request: HttpRequestData ->
                    endpointURL = request.url.toString()
                    respondOk()
                }

                client.use { client.post(path, body) }

                assertEquals("${client.baseURL}$path", endpointURL)
            }

            @Test
            fun `Specified Content-Type should be honored`() = runBlockingTest {
                var contentType: String? = null
                val client = makeTestClient { request: HttpRequestData ->
                    contentType = request.body.contentType.toString()
                    respondOk()
                }

                client.use { client.post(path, body) }

                assertEquals(body.contentType!!.toString(), contentType)
            }

            @Test
            fun `Request body should be the parcel serialized`() = runBlockingTest {
                var requestBody: ByteArray? = null
                val client = makeTestClient { request: HttpRequestData ->
                    assertTrue(request.body is OutgoingContent.ByteArrayContent)
                    requestBody = (request.body as OutgoingContent.ByteArrayContent).bytes()
                    respondOk()
                }

                client.use { client.post(path, body) }

                assertEquals(body.bytes().asList(), requestBody?.asList())
            }

            @Test
            fun `No Authorization header should be set by default`() = runBlockingTest {
                var authorizationHeader: String? = null
                val client = makeTestClient { request: HttpRequestData ->
                    authorizationHeader = request.headers["Authorization"]
                    respondOk()
                }

                client.use { client.post(path, body) }

                assertNull(authorizationHeader)
            }

            @Test
            fun `Authorization should be set if requested`() = runBlockingTest {
                val expectedAuthorizationHeader = "Foo bar"
                var actualAuthorizationHeader: String? = null
                val client = makeTestClient { request: HttpRequestData ->
                    actualAuthorizationHeader = request.headers["Authorization"]
                    respondOk()
                }

                client.use { client.post(path, body, expectedAuthorizationHeader) }

                assertEquals(expectedAuthorizationHeader, actualAuthorizationHeader)
            }
        }

        @Nested
        inner class Response {
            @Test
            fun `HTTP 20X should be regarded a successful delivery`() = runBlockingTest {
                val client = makeTestClient { respond("", HttpStatusCode.Accepted) }

                client.use { client.post(path, body) }
            }

            @Test
            fun `HTTP 30X responses should be regarded protocol violations by the server`() {
                val client = makeTestClient { respond("", HttpStatusCode.Found) }

                client.use {
                    val exception = assertThrows<ServerBindingException> {
                        runBlockingTest { client.post(path, body) }
                    }

                    assertEquals(
                        "Received unexpected status (${HttpStatusCode.Found})",
                        exception.message
                    )
                }
            }

            @Test
            fun `Other 40X responses should be regarded protocol violations by the client`() {
                val client = makeTestClient { respondError(HttpStatusCode.BadRequest) }

                client.use {
                    val exception = assertThrows<ClientBindingException> {
                        runBlockingTest { client.post(path, body) }
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
                        runBlockingTest { client.post(path, body) }
                    }

                    assertEquals(
                        "The server was unable to fulfil the request " +
                            "(${HttpStatusCode.BadGateway})",
                        exception.message
                    )
                }
            }
        }

        @Test
        fun `Failing to resolve DNS record should throw a ServerConnectionException`() {
            // Use a real client to try to open an actual network connection
            val client = PoWebClient.initRemote("foo.this-cannot-be-a-tld")

            client.use {
                val exception = assertThrows<ServerConnectionException> {
                    runBlocking { client.post(path, body) }
                }

                assertEquals("Failed to resolve DNS for ${client.baseURL}", exception.message)
                assertTrue(exception.cause is UnknownHostException)
            }
        }

        @Test
        fun `TCP connection issues should throw a ServerConnectionException`() {
            // Use a real client to try to open an actual network connection
            val client = PoWebClient.initRemote(NON_ROUTABLE_IP_ADDRESS)

            client.use {
                val exception = assertThrows<ServerConnectionException> {
                    runBlocking { client.post(path, body) }
                }

                assertEquals("Failed to connect to ${client.baseURL}$path", exception.message)
                assertTrue(exception.cause is SocketException)
            }
        }
    }

    @Nested
    inner class WebSocketConnection : WebSocketTestCase(false) {
        private val hostName = "127.0.0.1"
        private val port = 13276
        private val path = "/v1/the-endpoint"

        @Test
        fun `Failing to connect to the server should throw an exception`() {
            // Connect to an invalid port
            val client = PoWebClient.initLocal(mockWebServer.port - 1)

            client.use {
                val exception = assertThrows<ServerConnectionException> {
                    runBlocking { client.wsConnect(path) {} }
                }

                assertEquals("Server is unreachable", exception.message)
                assertTrue(exception.cause is ConnectException)
            }
        }

        @Test
        fun `Failing to upgrade the connection to WebSocket should throw an exception`() {
            val client = PoWebClient.initRemote("example.com")

            client.use {
                val exception = assertThrows<ServerConnectionException> {
                    runBlocking { client.wsConnect(path) {} }
                }

                assertEquals("Server is unreachable", exception.message)
                assertTrue(exception.cause is ProtocolException)
            }
        }

        @Test
        fun `Losing the connection abruptly should throw an exception`(): Unit = runBlocking {
            val client = PoWebClient.initLocal(mockWebServer.port)
            setListenerActions(ServerShutdownAction())

            client.use {
                val exception = assertThrows<ServerConnectionException> {
                    runBlocking {
                        client.wsConnect(path) {
                            incoming.receive()
                        }
                    }
                }

                assertEquals("Connection was closed abruptly", exception.message)
                assertTrue(exception.cause is EOFException)
            }
        }

        @Test
        fun `Client should use WS if TLS is not required`() = runBlockingTest {
            val wsRequest = mockWSConnect(false) {}

            assertEquals(URLProtocol.WS, wsRequest.url.protocol)
        }

        @Test
        fun `Client should use WSS if TLS is required`() = runBlockingTest {
            val wsRequest = mockWSConnect(true) {}

            assertEquals(URLProtocol.WSS, wsRequest.url.protocol)
        }

        @Test
        fun `Client should connect to specified host and port`(): Unit = runBlockingTest {
            val wsRequest = mockWSConnect(true) {}

            assertEquals(hostName, wsRequest.url.host)
            assertEquals(port, wsRequest.url.port)
        }

        @Test
        fun `Client should connect to specified path`() = runBlocking {
            val wsRequest = mockWSConnect {}

            assertEquals(path, wsRequest.url.encodedPath)
        }

        @Test
        fun `Request headers should be honored`() = runBlocking {
            val header1 = Pair("x-h1", "value1")
            val header2 = Pair("x-h2", "value2")

            val wsRequest = mockWSConnect(headers = listOf(header1, header2)) {}

            assertEquals(header1.second, wsRequest.headers[header1.first])
            assertEquals(header2.second, wsRequest.headers[header2.first])
        }

        @Test
        fun `Specified block should be called`(): Unit = runBlocking {
            setListenerActions(CloseConnectionAction())
            val client = PoWebClient.initLocal(mockWebServer.port)

            var wasBlockRun = false
            client.wsConnect(path) { wasBlockRun = true }

            assertTrue(wasBlockRun)
        }

        private suspend fun mockWSConnect(
            useTls: Boolean = false,
            headers: List<Pair<String, String>>? = null,
            block: suspend DefaultClientWebSocketSession.() -> Unit
        ): HttpRequestData {
            val client = PoWebClient(hostName, port, useTls)
            val ktorClientManager = MockKtorClientManager()
            client.ktorClient = ktorClientManager.ktorClient

            ktorClientManager.useClient {
                client.wsConnect(path, headers, block)
            }

            return ktorClientManager.request
        }
    }
}
