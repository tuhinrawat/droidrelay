package app.relay.spike

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import app.relay.protocol.RelayCodec
import app.relay.protocol.RelayMessage
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finds the Mac on the local Wi-Fi (Bonjour/NSD `_relay._tcp`) and keeps one
 * TCP connection open to it, speaking protocol v0 (plaintext; spike only).
 */
object MacLink {
    private const val SERVICE_TYPE = "_relay._tcp."
    private const val RECONNECT_DELAY_SECONDS = 5L

    enum class State { STOPPED, SEARCHING, CONNECTING, CONNECTED }

    @Volatile var state = State.STOPPED
        private set
    @Volatile var macName: String? = null
        private set

    private lateinit var appContext: Context
    private val io = Executors.newSingleThreadScheduledExecutor()
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var running = false

    @Synchronized
    fun start(context: Context) {
        if (running) return
        appContext = context.applicationContext
        SpikeLog.init(appContext)
        running = true
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("relay-nsd").apply {
            setReferenceCounted(false)
            acquire()
        }
        startDiscovery()
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        stopDiscovery()
        multicastLock?.release()
        multicastLock = null
        closeSocket("stopped")
        setState(State.STOPPED)
    }

    /** Sends a message if connected; silently drops it otherwise (spike behaviour). */
    fun send(message: RelayMessage) {
        val line = RelayCodec.encode(message)
        io.execute {
            val w = writer ?: return@execute
            try {
                w.write(line)
                w.write("\n")
                w.flush()
            } catch (e: Exception) {
                closeSocket("write failed: ${e.message}")
                scheduleReconnect()
            }
        }
    }

    // ---- Discovery -------------------------------------------------------

    private fun startDiscovery() {
        if (!running) return
        setState(State.SEARCHING)
        val manager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = manager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) =
                SpikeLog.log("Searching Wi-Fi for a Mac…")
            override fun onServiceFound(service: NsdServiceInfo) {
                if (state == State.SEARCHING) resolve(service)
            }
            override fun onServiceLost(service: NsdServiceInfo) =
                SpikeLog.log("Mac went away from the network: ${service.serviceName}")
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                SpikeLog.log("Discovery failed to start (code $errorCode)")
                scheduleReconnect()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discovery = listener
        manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun stopDiscovery() {
        val listener = discovery ?: return
        discovery = null
        runCatching { nsd?.stopServiceDiscovery(listener) }
    }

    @Suppress("DEPRECATION") // resolveService is deprecated on API 34 but is the only API on 26–33.
    private fun resolve(service: NsdServiceInfo) {
        val manager = nsd ?: return
        manager.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                // FAILURE_ALREADY_ACTIVE is normal when the Mac is found twice.
                if (errorCode != NsdManager.FAILURE_ALREADY_ACTIVE) {
                    SpikeLog.log("Could not resolve ${info.serviceName} (code $errorCode)")
                }
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host ?: return
                SpikeLog.log("Found Mac \"${info.serviceName}\" at ${host.hostAddress}:${info.port}")
                stopDiscovery()
                io.execute { connect(InetSocketAddress(host, info.port)) }
            }
        })
    }

    // ---- Connection ------------------------------------------------------

    private fun connect(address: InetSocketAddress) {
        if (!running || socket != null) return
        setState(State.CONNECTING)
        val s = Socket()
        try {
            s.connect(address, 5_000)
            s.tcpNoDelay = true
            s.keepAlive = true
            socket = s
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            setState(State.CONNECTED)
            SpikeLog.log("Connected to Mac")
            send(hello())
            send(battery())
            NotificationStore.all().asReversed().forEach { send(it.toMessage()) }
            Thread({ readLoop(s) }, "relay-reader").start()
            schedulePing(s)
        } catch (e: Exception) {
            runCatching { s.close() }
            SpikeLog.log("Connect failed: ${e.message}")
            scheduleReconnect()
        }
    }

    private fun readLoop(s: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            while (running) {
                val line = reader.readLine() ?: break
                handle(RelayCodec.decode(line) ?: continue)
            }
        } catch (e: Exception) {
            SpikeLog.log("Read failed: ${e.message}")
        }
        if (socket === s) {
            closeSocket("Mac disconnected")
            scheduleReconnect()
        }
    }

    private fun handle(message: RelayMessage) {
        when (message) {
            is RelayMessage.Hello -> {
                macName = message.name
                SpikeLog.log("Mac says hello: ${message.name}")
            }
            RelayMessage.Ping -> send(RelayMessage.Pong)
            is RelayMessage.NotificationReply -> {
                val result = RelayNotificationListener.reply(appContext, message.id, message.text)
                send(result)
            }
            else -> Unit
        }
    }

    private fun schedulePing(s: Socket) {
        io.schedule({
            if (socket === s) {
                send(RelayMessage.Ping)
                send(battery())
                schedulePing(s)
            }
        }, 30, TimeUnit.SECONDS)
    }

    private fun scheduleReconnect() {
        if (!running) return
        setState(State.SEARCHING)
        io.schedule({
            if (running && socket == null) {
                stopDiscovery()
                startDiscovery()
            }
        }, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS)
    }

    private fun closeSocket(reason: String) {
        val s = socket ?: return
        socket = null
        writer = null
        macName = null
        runCatching { s.close() }
        SpikeLog.log("Connection closed: $reason")
    }

    private fun setState(newState: State) {
        if (state != newState) {
            state = newState
            SpikeLog.log("Link: $newState")
        }
    }

    // ---- Device info -----------------------------------------------------

    private fun hello(): RelayMessage.Hello {
        val prefs = appContext.getSharedPreferences("relay", Context.MODE_PRIVATE)
        val id = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }
        val name = Settings.Global.getString(appContext.contentResolver, "device_name") ?: Build.MODEL
        return RelayMessage.Hello(id, name, "android", BuildConfigInfo.VERSION)
    }

    private fun battery(): RelayMessage.Battery {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return RelayMessage.Battery(
            level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            charging = bm.isCharging,
        )
    }
}

/** Avoids enabling BuildConfig generation just for one string. */
object BuildConfigInfo {
    const val VERSION = "0.1-spike"
}
