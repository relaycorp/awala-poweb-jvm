package tech.relaycorp.poweb

import com.nhaarman.mockitokotlin2.spy
import com.nhaarman.mockitokotlin2.verify
import io.ktor.client.engine.okhttp.OkHttpEngine
import io.ktor.client.features.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.HttpRequestData
import io.ktor.http.URLProtocol
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
import java.io.EOFException
import java.net.ConnectException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@KtorExperimentalAPI
class PoWebClientTest {
    @Nested
    @Suppress("RedundantInnerClassModifier")
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
    @Suppress("RedundantInnerClassModifier")
    @ExperimentalCoroutinesApi
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
