package com.th4rv4.zukinav.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.th4rv4.zukinav.protocol.SuzukiFrames
import com.th4rv4.zukinav.util.AppLog
import java.util.UUID

/**
 * GATT client for the cluster, set up the way Suzuki Ride Connect does it:
 * request MTU 250, discover services, take the 4th service, write frames to
 * its 1st characteristic and enable notifications on its 2nd.
 *
 * All GATT work runs on one background thread. Writes are queued and sent one
 * at a time; a newer nav frame replaces a nav frame still waiting in the queue.
 */
@SuppressLint("MissingPermission") // Callers check BLUETOOTH_CONNECT first.
class ClusterLink(private val context: Context, private val listener: Listener) {

    enum class State { DISCONNECTED, CONNECTING, READY }

    interface Listener {
        fun onStateChanged(state: State)
        fun onNotification(value: ByteArray) {}
    }

    private val thread = HandlerThread("cluster-gatt").apply { start() }
    private val h = Handler(thread.looper)

    @Volatile var state: State = State.DISCONNECTED
        private set
    @Volatile var deviceName: String? = null
        private set

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    private class Pending(val frame: ByteArray, val coalesceKey: String?)
    private val queue = ArrayDeque<Pending>()
    private var inFlight = false
    private var lastAddress: String? = null
    private var restarts = 0

    /** This connection has been set up already (see onConnectionStateChange). */
    private var setUpStarted = false

    /** Already threw away Android's saved copy of the services on this connect. */
    private var rereadServices = false

    /** Writes Android refused in a row; see [pump]. */
    private var rejected = 0
    private val writeTimeout = Runnable {
        AppLog.d("BLE", "Write timed out, moving on")
        inFlight = false
        pump()
    }

    fun connect(address: String, background: Boolean) = h.post {
        restarts = 0
        open(address, background)
    }

    /**
     * Throws this connection away and opens a new one, at most twice per
     * [connect]; after that the link is left down for the app to retry.
     */
    private fun startOver(why: String) {
        val address = lastAddress
        closeGatt()
        if (address == null || restarts >= 2) {
            AppLog.d("BLE", "$why, giving up on this try")
            setState(State.DISCONNECTED)
            return
        }
        restarts++
        AppLog.d("BLE", "$why, starting the link over")
        setState(State.CONNECTING)
        h.postDelayed({ if (gatt == null && state == State.CONNECTING) open(address, false) }, 500)
    }

    /** The link connected but never got as far as READY. */
    private val setupCheck = Runnable {
        if (gatt != null && state != State.READY) startOver("Dash didn't finish setting up in ${SETUP_WAIT_MS / 1000} s")
    }

    private fun open(address: String, background: Boolean) {
        closeGatt()
        lastAddress = address
        rereadServices = false
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            AppLog.d("BLE", "Bluetooth is off")
            setState(State.DISCONNECTED)
            return
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            AppLog.d("BLE", "Bad address $address")
            setState(State.DISCONNECTED)
            return
        }
        deviceName = device.name
        AppLog.d("BLE", "Connecting to ${device.name ?: "?"} ($address)${if (background) ", waiting for it to appear" else ""}")
        setState(State.CONNECTING)
        // autoConnect=true lets Android connect whenever the bike powers on,
        // without us polling; it is slower for a device that is already there.
        gatt = device.connectGatt(context, background, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, h)
    }

    fun disconnect() = h.post {
        closeGatt()
        setState(State.DISCONNECTED)
    }

    fun release() {
        disconnect()
        h.post { thread.quitSafely() }
    }

    /**
     * Queue a frame. Frames with the same non-null [coalesceKey] replace each
     * other while waiting, so a slow link never builds a backlog of stale turns.
     */
    fun send(frame: ByteArray, coalesceKey: String? = null) = h.post {
        if (state != State.READY) return@post
        if (coalesceKey != null) {
            val i = queue.indexOfFirst { it.coalesceKey == coalesceKey }
            if (i >= 0) { queue[i] = Pending(frame, coalesceKey); return@post }
        }
        if (queue.size > 32) queue.removeFirst()
        queue.addLast(Pending(frame, coalesceKey))
        pump()
    }

    private fun pump() {
        if (inFlight) return
        val g = gatt ?: return
        val c = writeChar ?: return
        val next = queue.removeFirstOrNull() ?: return
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, next.frame, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = type
            @Suppress("DEPRECATION")
            c.value = next.frame
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
        if (ok) {
            rejected = 0
            inFlight = true
            h.postDelayed(writeTimeout, 1_000)
        } else {
            rejected++
            if (rejected == 1) AppLog.d("BLE", "Write rejected, retrying shortly")
            if (rejected >= MAX_REJECTED) {
                // The link looks up but takes nothing: start it over instead of retrying forever.
                AppLog.d("BLE", "Dash took no messages for ${MAX_REJECTED * 50 / 1000} s, reconnecting")
                rejected = 0
                closeGatt()
                setState(State.DISCONNECTED)
                return
            }
            queue.addFirst(next)
            h.postDelayed({ pump() }, 50)
        }
    }

    private fun setState(s: State) {
        if (state == s) return
        state = s
        listener.onStateChanged(s)
    }

    private fun closeGatt() {
        setUpStarted = false
        h.removeCallbacks(setupCheck)
        h.removeCallbacks(writeTimeout)
        rejected = 0
        queue.clear()
        inFlight = false
        lastIncoming = null
        writeChar = null
        notifyChar = null
        gatt?.let {
            try { it.disconnect() } catch (_: Exception) {}
            try { it.close() } catch (_: Exception) {}
        }
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g != gatt) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Android can report the same connection as connected twice (seen
                // with a paired dash). Setting up again on top of the first set-up
                // jams the link, so only the first one counts.
                if (setUpStarted && state == State.READY && lastIncoming != null) {
                    // Set up and the dash is talking: the link works, so
                    // there's nothing to start over.
                    AppLog.d("BLE", "Connected again, but the dash is talking: carrying on")
                    return
                }
                if (setUpStarted) {
                    // A second time means the first was a link Android still
                    // thought was up; set up on both and nothing gets through.
                    startOver("Connected again on the same link")
                    return
                }
                setUpStarted = true
                h.removeCallbacks(setupCheck)
                h.postDelayed(setupCheck, SETUP_WAIT_MS)
                AppLog.d("BLE", "Connected (status $status), requesting MTU 250")
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (!g.requestMtu(250)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                AppLog.d("BLE", "Disconnected (status $status)")
                closeGatt()
                setState(State.DISCONNECTED)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (g != gatt) return
            AppLog.d("BLE", "MTU $mtu (status $status)")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g != gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                AppLog.d("BLE", "Service discovery failed ($status)")
                g.disconnect()
                return
            }
            logServices(g.services)
            val svc = pickService(g.services)
            if (svc == null) {
                // Android keeps a copy of a paired device's services and may
                // show an old one, taken while the dash wasn't ready. Throw
                // the copy away and read the services again, once.
                if (!rereadServices && forgetServices(g)) {
                    rereadServices = true
                    AppLog.d("BLE", "No dash service in Android's saved copy, reading the services again")
                    h.postDelayed({ if (gatt == g) g.discoverServices() }, 500)
                    return
                }
                AppLog.d("BLE", "No usable service found on this device")
                g.disconnect()
                return
            }
            writeChar = svc.characteristics[0]
            notifyChar = svc.characteristics[1]
            AppLog.d("BLE", "Using service ${svc.uuid}, write ${writeChar!!.uuid}, notify ${notifyChar!!.uuid}")
            if (!enableNotify(g, notifyChar!!)) setState(State.READY)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (g != gatt) return
            AppLog.d("BLE", "Notifications enabled (status $status)")
            setState(State.READY)
            pump()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (g != gatt) return
            h.removeCallbacks(writeTimeout)
            if (status != BluetoothGatt.GATT_SUCCESS) AppLog.d("BLE", "Write status $status")
            inFlight = false
            pump()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (g != gatt) return
            onIncoming(value)
        }

        @Deprecated("Pre-Android 13 variant")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (g != gatt) return
            if (Build.VERSION.SDK_INT < 33) onIncoming(c.value ?: return)
        }
    }

    private var lastIncoming: String? = null

    /** The bike repeats its frame every 5 s; log it only when it changes. */
    private fun onIncoming(value: ByteArray) {
        val hex = SuzukiFrames.hex(value)
        if (hex != lastIncoming) {
            AppLog.d("BLE", "<- $hex")
            lastIncoming = hex
        }
        listener.onNotification(value)
    }

    /**
     * Android's hidden BluetoothGatt.refresh(): forgets the saved copy of the
     * device's services. Widely used by Bluetooth apps; false if unavailable.
     */
    private fun forgetServices(g: BluetoothGatt): Boolean = try {
        g.javaClass.getMethod("refresh").invoke(g) as? Boolean ?: false
    } catch (e: Exception) {
        false
    }

    private fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic): Boolean {
        val props = c.properties
        val canNotify = props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val canIndicate = props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        if (!canNotify && !canIndicate) return false
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(CCCD) ?: return false
        val value = if (canNotify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    private fun canWrite(c: BluetoothGattCharacteristic) =
        c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

    /**
     * The Suzuki app hard-codes index 3. Use that when it looks right; if this
     * phone's stack lists services in another order, fall back to the first
     * non-standard service whose 1st characteristic is writable.
     */
    private fun pickService(services: List<BluetoothGattService>): BluetoothGattService? {
        services.getOrNull(3)?.let { s ->
            if (s.characteristics.size >= 2 && canWrite(s.characteristics[0])) return s
        }
        return services.firstOrNull { s ->
            !isStandard(s.uuid) && s.characteristics.size >= 2 && canWrite(s.characteristics[0])
        }
    }

    private fun isStandard(u: UUID): Boolean {
        val s = u.toString()
        return s.endsWith("-0000-1000-8000-00805f9b34fb") &&
            (s.startsWith("00001800") || s.startsWith("00001801") || s.startsWith("0000180a"))
    }

    private fun logServices(services: List<BluetoothGattService>) {
        services.forEachIndexed { i, s ->
            AppLog.d("BLE", "service[$i] ${s.uuid}")
            s.characteristics.forEachIndexed { j, c ->
                AppLog.d("BLE", "  char[$j] ${c.uuid} props=0x%02X".format(c.properties))
            }
        }
    }

    private companion object {
        /** From connected to ready: a dash takes well under a second. */
        const val SETUP_WAIT_MS = 8_000L

        /** 3 s of refused writes, 50 ms apart. */
        const val MAX_REJECTED = 60
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
