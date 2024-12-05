package com.duckduckgo.app.dns.socket_pool

class SocketClientException(
    message: String?,
    t: Throwable?
) :
    RuntimeException(message, t)
