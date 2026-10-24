// Finds RigHelm servers on the LAN.
//
// Servers broadcast a beacon every 2 s on UDP 4534 and answer a
// `FTDX3000-DISCOVER` probe sent to that port. Android drops incoming
// broadcasts unless the app holds a Wi-Fi multicast lock (the app takes one
// while discovery runs), and some access points filter broadcasts anyway, so
// the probe also goes straight to every address on the local subnet: the
// answers are ordinary unicast.

package com.righelm.core

class Discovery {
    /** Called through [callback] whenever the list changes. */
    var onUpdate: ((List<ServerBeacon>) -> Unit)? = null
    /** Runs callbacks on the caller's thread of choice (the app's main thread). */
    var callback: (Runnable) -> Unit = { it.run() }

    private val lock = Any()
    private var running = false
    private val found = mutableMapOf<String, ServerBeacon>()
    private var probeRequested = false
    /** Bumped by every start, so a stopped thread can't come back to life. */
    private var generation = 0

    val servers: List<ServerBeacon>
        get() = synchronized(lock) { found.values.toList() }.sortedBy { it.name }

    fun start() {
        val gen = synchronized(lock) {
            if (running) return
            running = true
            generation += 1
            generation
        }
        Thread({ run(gen) }, "righelm-discovery").apply { isDaemon = true }.start()
    }

    fun stop() {
        synchronized(lock) { running = false }
    }

    /** Probe again right away (e.g. on pull-to-refresh). */
    fun probeNow() {
        synchronized(lock) { probeRequested = true }
    }

    private fun isRunning(gen: Int) = synchronized(lock) { running && generation == gen }

    private fun run(gen: Int) {
        // Port 4534 also catches beacons; when another program holds it, any
        // port gets the probe answers.
        val sock = try {
            UdpSocket(RemoteProtocol.DISCOVERY_PORT, reusePort = true, broadcast = true)
        } catch (e: Exception) {
            try {
                UdpSocket(0, broadcast = true)
            } catch (e: Exception) {
                return
            }
        }
        try {
            var lastProbe = 0L
            var probes = 0
            while (isRunning(gen)) {
                // Quickly at first, then every 5 s.
                val every = if (probes < 3) 1500L else 5000L
                val asked = synchronized(lock) { probeRequested.also { probeRequested = false } }
                if (asked || System.currentTimeMillis() - lastProbe > every) {
                    sendProbes(sock)
                    lastProbe = System.currentTimeMillis()
                    probes += 1
                }
                val d = sock.receive(0.25)
                if (d != null) {
                    val host = d.from.hostAddress
                    val beacon = host?.let { ServerBeacon.parse(String(d.bytes, Charsets.UTF_8), it) }
                    if (beacon != null) {
                        synchronized(lock) { found[beacon.id] = beacon }
                        publish()
                    }
                }
                val expired = synchronized(lock) {
                    val now = System.currentTimeMillis()
                    found.values.removeAll { now - it.lastSeen >= 12_000 }
                }
                if (expired) publish()
            }
        } finally {
            sock.close()
        }
    }

    private fun sendProbes(sock: UdpSocket) {
        val port = RemoteProtocol.DISCOVERY_PORT
        sock.send(RemoteProtocol.PROBE, 0xFFFF_FFFFL, port)
        for (iface in lanInterfaces()) {
            sock.send(RemoteProtocol.PROBE, iface.broadcast, port)
            for (host in sweep(iface)) {
                sock.send(RemoteProtocol.PROBE, host, port)
            }
        }
    }

    private fun publish() {
        val list = servers
        callback(Runnable { onUpdate?.invoke(list) })
    }

    companion object {
        /**
         * Every other host on the interface's subnet; for a subnet bigger than
         * /22, just the /24 around this device.
         */
        fun sweep(iface: NetInterface): List<Long> {
            var mask = iface.netmask
            if (mask < 0xFFFF_FC00L) mask = 0xFFFF_FF00L
            val net = iface.address and mask
            val bcast = (net or mask.inv()) and 0xFFFF_FFFFL
            if (bcast <= net + 1) return emptyList()
            return (net + 1 until bcast).filter { it != iface.address }
        }
    }
}
