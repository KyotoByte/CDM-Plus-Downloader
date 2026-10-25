package io.github.kyotobyte.cdmplus.engine

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

/** Tries IPv4 addresses first: avoids long stalls on networks with broken IPv6. */
object Ipv4FirstDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
}
