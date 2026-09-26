package xdm.integration


import xdm.core.util.Logger
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class HttpServer(
    private val host: String,
    private val port: Int,
    private val requestListener: (ctx: RequestContext) -> Unit,
    private val onSuccess: () -> Unit, private val onFailure: () -> Unit,
) {
    private val serverSocket = ServerSocket()

    fun start() {
        Thread {
            // Only a failed bind means "the port is taken" (onFailure hands off to a running copy);
            // an error while starting the UI must not be reported as that.
            try {
                serverSocket.bind(InetSocketAddress(host, port))
            } catch (ex: Exception) {
                Logger.info(ex)
                onFailure()
                return@Thread
            }
            try {
                onSuccess()
            } catch (ex: Exception) {
                Logger.error("INTEGRATION", "Startup failed", ex)
            }
            while (!serverSocket.isClosed) {
                process()
            }
        }.start()
    }

    private fun process() {
        try {
            val socket = serverSocket.accept()
            if (!socket.inetAddress.isLoopbackAddress) {
                Logger.info("INTEGRATION", "Rejected non-loopback connection from ${socket.inetAddress.hostAddress}")
                socket.close()
                return
            }
            processRequest(socket)
        } catch (e: IOException) {
            if (!serverSocket.isClosed) Logger.info(e)
        }
    }

    fun stop() {
        try {
            serverSocket.close()
        } catch (e: IOException) {
            // ignore
        }
    }

    private fun processRequest(socket: Socket) {
        Thread {
            try {
                socket.use {
                    while (true) {
                        val ctx = HttpParser.parseContext(socket)
                        requestListener(ctx)
                        if (!ctx.keepAlive) {
                            break
                        }
                    }
                }
            } catch (_: HttpParser.ConnectionClosedException) {
                // Client closed an idle kept-alive connection.
            } catch (e: Exception) {
                Logger.info(e.message)
            }
        }.start()
    }
}
