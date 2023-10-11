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
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.util.InternalAPI
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tech.relaycorp.poweb.websocket.CloseConnectionAction
import tech.relaycorp.poweb.websocket.WebSocketTestCase
import tech.relaycorp.relaynet.bindings.pdc.ServerBindingException
import tech.relaycorp.relaynet.bindings.pdc.ServerConnectionException
import java.io.EOFException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

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

                assertEquals("http://127.0.0.1:276/v1", client.baseHttpUrl)
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

                assertEquals("https://$hostName:443/v1", client.baseHttpUrl)
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
            fun `Request should be made with HTTP POST`() = runBlocking {
                var method: HttpMethod? = null
                val client = makeTestClient { request: HttpRequestData ->
                    method = request.method
                    respondOk()
                }

                client.use { client.post(path, body) }

                assertEquals(HttpMethod.Post, method)
            }

            @Test
            fun `Specified path should be honored`() = runBlocking {
                var endpointURL: String? = null
                val client = makeTestClient { request: HttpRequestData ->
                    endpointURL = request.url.toString()
                    respondOk()
                }

                client.use { client.post(path, body) }

                assertEquals("${client.baseHttpUrl}$path", endpointURL)
            }

            @Test
            fun `Specified Content-Type should be honored`() = runBlocking {
                var contentType: String? = null
                val client = makeTestClient { request: HttpRequestData ->
                    contentType = request.body.contentType.toString()
                    respondOk()
                }

                client.use { client.post(path, body) }

                assertEquals(body.contentType!!.toString(), contentType)
            }

            @Test
            fun `Request body should be the parcel serialized`() = runBlocking {
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
            fun `No Authorization header should be set by default`() = runBlocking {
                var authorizationHeader: String? = null
                val client = makeTestClient { request: HttpRequestData ->
                    authorizationHeader = request.headers["Authorization"]
                    respondOk()
                }

                client.use { client.post(path, body) }

                assertNull(authorizationHeader)
            }

            @Test
            fun `Authorization should be set if requested`() = runBlocking {
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
            fun `HTTP 20X should be regarded a successful delivery`(): Unit = runBlocking {
                val client = makeTestClient { respond("", HttpStatusCode.Accepted) }

                client.use { client.post(path, body) }
            }

            @Test
            fun `HTTP 30X responses should be regarded protocol violations by the server`() {
                val client = makeTestClient { respond("", HttpStatusCode.Found) }

                client.use {
                    val exception = assertThrows<ServerBindingException> {
                        runBlocking { client.post(path, body) }
                    }

                    assertEquals(
                        "Unexpected redirect (${HttpStatusCode.Found})",
                        exception.message
                    )
                }
            }

            @Test
            fun `HTTP 40X responses should be regarded protocol violations by the client`() {
                val status = HttpStatusCode.BadRequest
                val client = makeTestClient { respondError(status) }

                client.use {
                    val exception = assertThrows<PoWebClientException> {
                        runBlocking { client.post(path, body) }
                    }

                    assertEquals(status, exception.responseStatus)
                }
            }

            @Test
            fun `HTTP 50X responses should throw a ServerConnectionException`() {
                val client = makeTestClient { respondError(HttpStatusCode.BadGateway) }

                client.use {
                    val exception = assertThrows<ServerConnectionException> {
                        runBlocking { client.post(path, body) }
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
            val client = makeTestClient { throw UnknownHostException("foo") }

            client.use {
                val exception = assertThrows<ServerConnectionException> {
                    runBlocking { client.post(path, body) }
                }

                assertEquals("Failed to resolve DNS for ${client.baseHttpUrl}", exception.message)
                assertTrue(exception.cause is UnknownHostException)
            }
        }

        @Test
        fun `TCP connection issues should throw a ServerConnectionException`() {
            val client = makeTestClient { throw SocketException("foo") }

            client.use {
                val exception = assertThrows<ServerConnectionException> {
                    runBlocking { client.post(path, body) }
                }

                assertEquals("Failed to connect to ${client.baseHttpUrl}$path", exception.message)
                assertTrue(exception.cause is SocketException)
            }
        }
    }

    @Nested
    inner class WebSocketConnection : WebSocketTestCase(false) {
        private val hostName = "127.0.0.1"
        private val path = "/the-endpoint"

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
        fun `Client should reconnect if the connection is lost abruptly`(): Unit = runBlocking {
            val connection1 = CloseConnectionAction()
            addServerConnection(connection1)
            val connection2 = CloseConnectionAction()
            addServerConnection(connection2)

            var connectionLossReplicated = false
            mockWSConnect {
                if (!connectionLossReplicated) {
                    connectionLossReplicated = true
                    throw EOFException("Connection lost")
                }
            }

            waitForConnectionClosure()
            val expectedDelta = 3.seconds.toJavaDuration()
            val delta = Duration.between(connection2.runDate!!, connection1.runDate!!)
            assertTrue(delta <= expectedDelta)
        }

        @Test
        fun `Client should reconnect if the connection timed-out`(): Unit = runBlocking {
            val connection1 = CloseConnectionAction()
            addServerConnection(connection1)
            val connection2 = CloseConnectionAction()
            addServerConnection(connection2)

            var connectionLossReplicated = false
            mockWSConnect {
                if (!connectionLossReplicated) {
                    connectionLossReplicated = true
                    throw SocketTimeoutException("Timeout")
                }
            }

            waitForConnectionClosure()
            val expectedDelta = 500.milliseconds.toJavaDuration()
            val delta = Duration.between(connection2.runDate!!, connection1.runDate!!)
            assertTrue(delta <= expectedDelta)
        }

        @Test
        fun `Client should use WS if TLS is not required`() = runBlocking {
            val client = PoWebClient(hostName, mockWebServer.port, false)

            assertTrue(client.baseWsUrl.startsWith("ws:"), "Actual URL: ${client.baseWsUrl}")
        }

        @Test
        fun `Client should use WSS if TLS is required`() = runBlocking {
            val client = PoWebClient(hostName, mockWebServer.port, true)

            assertTrue(client.baseWsUrl.startsWith("wss:"))
        }

        @Test
        fun `Client should connect to specified path`() = runBlocking {
            addServerConnection(CloseConnectionAction())

            mockWSConnect {}

            val request = mockWebServer.takeRequest()
            assertEquals("/v1$path", request.path)
        }

        @Test
        fun `Request headers should be honored`() = runBlocking {
            val header1 = Pair("x-h1", "value1")
            val header2 = Pair("x-h2", "value2")
            addServerConnection(CloseConnectionAction())

            mockWSConnect(listOf(header1, header2)) {}

            val request = mockWebServer.takeRequest()
            assertEquals(header1.second, request.headers[header1.first])
            assertEquals(header2.second, request.headers[header2.first])
        }

        @Test
        fun `Specified block should be called`(): Unit = runBlocking {
            addServerConnection(CloseConnectionAction())

            var wasBlockRun = false
            mockWSConnect { wasBlockRun = true }

            assertTrue(wasBlockRun)
        }

        private suspend fun mockWSConnect(
            headers: List<Pair<String, String>>? = null,
            block: suspend DefaultClientWebSocketSession.() -> Unit
        ) {
            val client = PoWebClient(hostName, mockWebServer.port, false)
            client.ktorClient = ktorWSClient

            client.use {
                client.wsConnect(path, headers, block)
            }
        }
    }
}
