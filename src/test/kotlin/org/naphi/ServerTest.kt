package org.naphi

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.naphi.RequestMethod.GET
import org.naphi.RequestMethod.POST
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder

class ServerTest {

    private val client = HttpUrlConnectionClient(connectionTimeout = 50, socketTimeout = 100)

    @Test
    fun `server should return OK hello world response`() {
        // given
        val body = "Hello, World!"
        val server = Server(port = 8090, handler = {
            Response(status = Status.OK, body = body, headers = HttpHeaders("Content-Length" to body.length.toString()))
        })

        // when
        val response = client.exchange(url = "http://localhost:8090", request = Request(path = "/", method = GET))

        // then
        assertThat(response.status).isEqualTo(Status.OK)
        assertThat(response.body).isEqualTo("Hello, World!")

        // cleanup
        server.close()
    }

    @Test
    fun `server should refuse to accept more connections that maxIncommingConnections`() {
        // given
        val server = Server(port = 8090, maxIncomingConnections = 1, handler = {
            Thread.sleep(100)
            Response(Status.OK)
        })

        // when
        val completedRequest = LongAdder() // we will be adding from multiple threads
        // we are starting 3 requests simultaneously
        val clientsThreadPool = Executors.newFixedThreadPool(3)
        repeat(times = 3) {
            clientsThreadPool.submit {
                try {
                    HttpUrlConnectionClient(connectionTimeout = 50, socketTimeout = 1000)
                            .exchange(url = "http://localhost:8090", request = Request(path = "/", method = GET))
                    completedRequest.increment()
                } catch (e: Exception) {
                    println(e)
                    throw e
                }
            }
        }
        // it should be enough for 3 request to complete if limiting was somehow broken
        clientsThreadPool.awaitTermination(500, TimeUnit.MILLISECONDS)

        // then
        assertThat(completedRequest.sum()).isEqualTo(2)

        // cleanup
        server.close()
    }

    @Test
    fun `server should echo body and headers`() {
        // given
        val body = "Echo!"
        val server = Server(port = 8090, maxIncomingConnections = 1, handler = { request ->
            Response(
                    status = Status.OK,
                    body = request.body,
                    headers = request.headers)
        })

        // when
        val response = client.exchange(
                url = "http://localhost:8090",
                request = Request(
                        path = "/",
                        method = POST,
                        headers = HttpHeaders("X-Custom-Header" to "ABC"),
                        body = body))

        // then
        assertThat(response.status).isEqualTo(Status.OK)
        assertThat(response.body).isEqualTo("Echo!")
        assertThat(response.headers["X-Custom-Header"]).containsExactly("ABC")

        // cleanup
        server.close()
    }

    @Test
    fun `server should throw bad request on random data`() {
        // given
        val server = Server(port = 8090, maxIncomingConnections = 1, handler = { Response(Status.OK) })

        // when
        val socket = Socket("localhost", 8090)
        val input = socket.getOutputStream().writer()
        input.write("NOT VALID HTTP REQUEST\n")
        input.flush()
        val response = socket.getInputStream().bufferedReader().readText()
        socket.close()

        // then
        assertThat(response).contains("400 Bad Request")

        // cleanup
        server.close()
    }
}