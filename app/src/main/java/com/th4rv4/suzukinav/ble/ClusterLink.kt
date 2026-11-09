package com.th4rv4.suzukinav.ble

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
import com.th4rv4.suzukinav.protocol.SuzukiFrames
import com.th4rv4.suzukinav.util.AppLog
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
    private val writeTimeout = Runnable {
        AppLog.d("BLE", "Write timed out, moving on")
        inFlight = false
        pump()
    }

    fun connect(address: String, background: Boolean) = h.post {
        closeGatt()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            AppLog.d("BLE", "Bluetooth is off")
            setState(State.DISCONNECTED)
            return@post
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            AppLog.d("BLE", "Bad address $address")
            setState(State.DISCONNECTED)
            return@post
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
            inFlight = true
            h.postDelayed(writeTimeout, 1_000)
        } else {
            AppLog.d("BLE", "Write rejected, retrying shortly")
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
        h.removeCallbacks(writeTimeout)
        queue.clear()
        inFlight = false
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
            AppLog.d("BLE", "MTU $mtu (status $status)")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                AppLog.d("BLE", "Service discovery failed ($status)")
                g.disconnect()
                return
            }
            logServices(g.services)
            val svc = pickService(g.services)
            if (svc == null) {
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
            AppLog.d("BLE", "Notifications enabled (status $status)")
            setState(State.READY)
            pump()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            h.removeCallbacks(writeTimeout)
            if (status != BluetoothGatt.GATT_SUCCESS) AppLog.d("BLE", "Write status $status")
            inFlight = false
            pump()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onIncoming(value)
        }

        @Deprecated("Pre-Android 13 variant")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) onIncoming(c.value ?: return)
        }
    }

    private fun onIncoming(value: ByteArray) {
        AppLog.d("BLE", "<- ${SuzukiFrames.hex(value)}")
        listener.onNotification(value)
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
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
