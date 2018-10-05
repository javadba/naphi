package org.naphi

import org.naphi.commons.IncrementingThreadFactory
import org.slf4j.LoggerFactory
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.SocketException
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

const val PROTOCOL = "HTTP/1.1"

data class Request(
        val path: String,
        val method: RequestMethod,
        val headers: HttpHeaders = HttpHeaders(),
        val body: String? = null) {
    companion object
}

data class Response(
        val status: Status,
        val headers: HttpHeaders = HttpHeaders(),
        val body: String? = null)

enum class RequestMethod {
    GET,
    HEAD,
    OPTIONS,
    POST,
    PUT,
    DELETE,
    TRACE,
    CONNECT;

    companion object {
        fun valueOfOrNull(method: String): RequestMethod? = values().firstOrNull { it.name == method }
    }
}

enum class Status(val code: Int, val reason: String) {
    OK(200, "OK"),
    BAD_REQUEST(400, "Bad Request"),
    INTERNAL_SERVER_ERROR(500, "Internal Server Error");
    // todo more status codes

    companion object {
        fun valueOfCode(code: Int) = values().first { it.code == code }
    }
}

class HttpHeaders(mapOfHeaders: Map<String, Collection<String>> = emptyMap()) {

    private val mapOfHeaders: Map<String, Collection<String>> = mapOfHeaders.mapKeys { (k, _) -> k.toLowerCase() }

    constructor(vararg pairs: Pair<String, String>)
            : this(pairs.asSequence()
            .groupBy { (name, _) -> name }
            .mapValues { (_, namesWithValues) -> namesWithValues.map { (_, values) -> values } }
            .toMap())

    val contentLength: Int = this["content-length"].firstOrNull()?.toIntOrNull() ?: 0
    val connection: String? = this["connection"].firstOrNull()
    val size: Int = mapOfHeaders.size

    fun asSequence() = mapOfHeaders.asSequence()
    operator fun get(key: String): Collection<String> = mapOfHeaders[key] ?: emptyList()
    operator fun plus(pair: Pair<String, String>) = HttpHeaders(mapOfHeaders + (pair.first to listOf(pair.second)))
    override fun toString(): String = "HttpHeaders($mapOfHeaders)"
}

typealias Handler = (Request) -> Response

class Server(
        val handler: Handler,
        val port: Int,
        val maxIncomingConnections: Int = 10,
        val maxWorkerThreads: Int = 50,
        val keepAliveTimeout: Duration = Duration.ofSeconds(30),
        val checkKeepAliveInterval: Duration = Duration.ofSeconds(1)
): AutoCloseable {

    private val logger = LoggerFactory.getLogger(Server::class.java)

    private val serverSocket = ServerSocket(port, maxIncomingConnections)
    private val handlerThreadPool = Executors.newFixedThreadPool(maxWorkerThreads, IncrementingThreadFactory("server-handler"))
    private val acceptingConnectionsThreadPool = Executors.newSingleThreadExecutor(IncrementingThreadFactory("server-connections-acceptor"))
    private val connectionPool = ConnectionPool(keepAliveTimeout, checkKeepAliveInterval)

    init {
        logger.info("Starting server on port $port")
        acceptingConnectionsThreadPool.submit(this::acceptConnections)
    }

    private fun acceptConnections() {
        while (!serverSocket.isClosed) {
            try {
                acceptConnection()
            } catch (e: Exception) {
                when {
                    e is InterruptedException -> throw e
                    e is SocketException && serverSocket.isClosed -> logger.trace("Server socket was closed", e)
                    else -> logger.error("Could not accept new connection", e)
                }
            }
        }
    }

    private fun acceptConnection() {
        val connection = Connection(serverSocket.accept())
        logger.debug("Accepted new connection ${connection.destination()}")
        handlerThreadPool.submit {
            connectionPool.addConnection(connection)
            try {
                handleConnection(connection)
            } catch (e: Exception) {
                when {
                    e is SocketException && connection.isClosed() -> logger.trace("Connection was closed", e)
                    else -> logger.warn("Could not handle connection", e)
                }
            }
        }
    }

    private fun handleConnection(connection: Connection) {
        val input = connection.getInputStream().bufferedReader()
        val output = PrintWriter(connection.getOutputStream())
        while (!connection.isClosed()) {
            var request: Request? = null
            val response = try {
                request = Request.fromRaw(input)
                connection.markAsAlive()
                handler(request)
            } catch (e: EmptyRequestException) { // Find a better way to detect close connections
                logger.trace("Connection was closed by client")
                connection.close()
                break
            } catch (e: RequestParseException) {
                logger.warn("Could not parse a request", e)
                Response(status = Status.BAD_REQUEST)
            }
            output.print(response.toRaw())
            output.flush()
            if (request?.headers?.connection == "close") {
                connection.close()
            }
        }
    }

    override fun close() {
        logger.info("Stopping server")
        serverSocket.close()
        handlerThreadPool.shutdown()
        handlerThreadPool.awaitTermination(1, TimeUnit.SECONDS)
        connectionPool.close()
        acceptingConnectionsThreadPool.shutdown()
        acceptingConnectionsThreadPool.awaitTermination(1, TimeUnit.SECONDS)
    }

    fun connectionsEstablished() = connectionPool.connectionsEstablished()
}

fun main(args: Array<String>) {
    Server(port = 8090, handler = {
        Response(status = Status.OK, body = "Hello, World!", headers = HttpHeaders("Connection" to "Keep-Alive", "Content-Length" to "Hello, World!".length.toString()))
    })
}