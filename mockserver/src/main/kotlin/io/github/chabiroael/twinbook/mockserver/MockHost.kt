package io.github.chabiroael.twinbook.mockserver

import java.time.LocalTime

/**
 * Runs the mock on the host, on a fixed loopback port, and prints one line per request as it
 * arrives: `<time> <method> <path>[?query]`.
 * Usage (tools/mock-host.sh): `mockserver <port>`. Runs until killed.
 */
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 8723
    val server = MockServer()
    server.onRequest = { r -> println("${LocalTime.now()} $r") }
    server.start(port)
    println("mock host listening on ${server.origin}")
    Thread.currentThread().join()
}
