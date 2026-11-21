package com.th4rv4.zukidashsim

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * The dash's Bluetooth side: advertises as an SF 250 dash and runs the same
 * service (fff0: write fff1, notify fff2) the real one has.
 */
@SuppressLint("MissingPermission")
class DashPeripheral(context: Context, private val events: Events) {

    interface Events {
        fun log(line: String)
        fun onFrame(frame: ByteArray)
        fun onLinkChanged()
    }

    private val ctx = context.applicationContext
    private val manager = ctx.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private val main = Handler(Looper.getMainLooper())

    private var server: BluetoothGattServer? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var wantOn = false

    var device: BluetoothDevice? = null
        private set
    var subscribed = false
        private set
    var advertising = false
        private set
    var resetting = false
        private set

    private val queue = ArrayDeque<ByteArray>()
    private var sending = false
    private val sendTimeout = Runnable {
        sending = false
        pump()
    }

    /**
     * Ignition on: the dash wakes up and can be found. Bluetooth comes on
     * with it, since it goes off with the ignition (see [powerOff]).
     */
    fun powerOn() {
        wantOn = true
        // Bluetooth still going off or on: this runs again when it's done.
        if (resetting) return
        val a = adapter ?: return events.log("This phone has no Bluetooth")
        if (!a.isEnabled) {
            if (resetting) return
            resetting = true
            events.log("Bluetooth on")
            events.onLinkChanged()
            @Suppress("DEPRECATION")
            a.enable()
            waitFor(a, on = true) {
                resetting = false
                events.onLinkChanged()
                if (wantOn) powerOn()
            }
            return
        }
        if (a.name != NAME) {
            a.setName(NAME)
            events.log("Phone's Bluetooth name set to $NAME")
            main.postDelayed({ if (wantOn) powerOn() }, 800)
            return
        }
        if (server == null) openServer() else startAdvertising()
    }

    /**
     * Ignition off: the dash goes dark. Bluetooth goes off too, so the phone
     * finds nothing at all, as with the real bike switched off. (With
     * Bluetooth left on, Android would still let the phone connect to this
     * phone and find no dash service.)
     */
    fun powerOff() {
        wantOn = false
        stopAdvertising()
        device?.let { d -> server?.cancelConnection(d) }
        server?.close()
        server = null
        notifyChar = null
        forget()
        val a = adapter
        if (a != null && a.isEnabled && !resetting) {
            resetting = true
            @Suppress("DEPRECATION")
            a.disable()
            waitFor(a, on = false) {
                resetting = false
                events.log("Bluetooth off: the dash is gone")
                events.onLinkChanged()
                // Switched back on while Bluetooth was going off.
                if (wantOn) powerOn()
            }
        }
        events.onLinkChanged()
    }

    /** The link drops but the dash stays on, as when the dash loses the phone for a moment. */
    fun dropLink() {
        val d = device ?: return events.log("Nothing connected to drop")
        dropping = d
        server?.cancelConnection(d)
        events.log("Dropping the link")
        makeSureDropped()
    }

    /** The link Drop Link is waiting to see go down; null once it has. */
    private var dropping: BluetoothDevice? = null

    fun send(frame: ByteArray) {
        if (!subscribed || device == null) return
        if (queue.size > 20) queue.removeFirst()
        queue.addLast(frame)
        pump()
    }

    private fun pump() {
        if (sending) return
        val d = device ?: return
        val c = notifyChar ?: return
        val s = server ?: return
        val f = queue.removeFirstOrNull() ?: return
        sending = true
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            s.notifyCharacteristicChanged(d, c, false, f) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.value = f
            @Suppress("DEPRECATION")
            s.notifyCharacteristicChanged(d, c, false)
        }
        if (!ok) {
            sending = false
            return
        }
        main.postDelayed(sendTimeout, 500)
    }

    private fun forget() {
        device = null
        subscribed = false
        queue.clear()
        sending = false
        main.removeCallbacks(sendTimeout)
    }

    /**
     * Android doesn't always let go of a link the phone started. If it hasn't
     * gone down after 3 s, switch Bluetooth off and on, which always drops it.
     * (The phone reconnecting by itself straight after is fine: that's a new link.)
     */
    private fun makeSureDropped() {
        main.postDelayed({
            if (dropping == null) {
                events.log("Dropped: link down")
                return@postDelayed
            }
            dropping = null
            events.log("Dropped: link still up, switching Bluetooth off and on")
            resetBluetooth()
        }, 3000)
    }

    private fun resetBluetooth() {
        val a = adapter ?: return
        resetting = true
        events.onLinkChanged()
        server?.close()
        server = null
        notifyChar = null
        advertising = false
        forget()
        @Suppress("DEPRECATION")
        a.disable()
        waitFor(a, on = false) {
            @Suppress("DEPRECATION")
            a.enable()
            waitFor(a, on = true) {
                resetting = false
                events.log("Bluetooth back on")
                events.onLinkChanged()
                if (wantOn) powerOn()
            }
        }
    }

    /** Runs [then] once Bluetooth is fully on or off (or after 10 s). */
    private fun waitFor(a: BluetoothAdapter, on: Boolean, tries: Int = 0, then: () -> Unit) {
        main.postDelayed({
            val done = if (on) a.state == BluetoothAdapter.STATE_ON else a.state == BluetoothAdapter.STATE_OFF
            if (done || tries > 20) then() else waitFor(a, on, tries + 1, then)
        }, 500)
    }

    private fun openServer() {
        val s = manager?.openGattServer(ctx, callback) ?: return events.log("Couldn't start the dash service")
        server = s
        val svc = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val write = BluetoothGattCharacteristic(
            WRITE,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val notify = BluetoothGattCharacteristic(
            NOTIFY,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        notify.addDescriptor(
            BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE),
        )
        // Write first, notify second: the order the app expects.
        svc.addCharacteristic(write)
        svc.addCharacteristic(notify)
        notifyChar = notify
        if (!s.addService(svc)) events.log("Couldn't add the dash service")
        // Advertising starts once the service is in (onServiceAdded).
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
            events.log("Advertising as $NAME")
            events.onLinkChanged()
        }

        override fun onStartFailure(errorCode: Int) {
            // Already advertising counts as fine.
            advertising = errorCode == ADVERTISE_FAILED_ALREADY_STARTED
            events.log("Advertising failed ($errorCode)")
            events.onLinkChanged()
        }
    }

    private fun startAdvertising() {
        if (!wantOn || device != null || server == null || advertising) return
        val adv = adapter?.bluetoothLeAdvertiser ?: return events.log("This phone can't advertise")
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(SERVICE))
            .build()
        adv.startAdvertising(settings, data, advertiseCallback)
    }

    private fun stopAdvertising() {
        if (!advertising) return
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {
        }
        advertising = false
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            main.post {
                if (status != BluetoothGatt.GATT_SUCCESS) events.log("Dash service failed ($status)")
                startAdvertising()
            }
        }

        override fun onConnectionStateChange(d: BluetoothDevice, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // Not yet "the phone": Android also reports links for other
                    // things (Android Auto, calls). It becomes the phone once
                    // it asks for the dash's readings (onDescriptorWriteRequest).
                    // connect() lets cancelConnection() drop it later.
                    server?.connect(d, false)
                    if (device == null) events.log("Link from ${d.address}")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED && d == device) {
                    dropping = null
                    forget()
                    events.log("Phone disconnected (status $status)")
                    startAdvertising()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED && device == null) {
                    // A link that never became the phone may have used up the
                    // advertisement (Android stops it on connect): start it again.
                    stopAdvertising()
                    startAdvertising()
                }
                events.onLinkChanged()
            }
        }

        override fun onMtuChanged(d: BluetoothDevice, mtu: Int) {
            main.post { events.log("MTU $mtu") }
        }

        override fun onCharacteristicWriteRequest(
            d: BluetoothDevice, requestId: Int, c: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            val v = value ?: return
            main.post { events.onFrame(v) }
        }

        override fun onDescriptorWriteRequest(
            d: BluetoothDevice, requestId: Int, desc: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            val on = value != null && value.isNotEmpty() && value[0].toInt() != 0
            main.post {
                if (desc.uuid != CCCD) return@post
                if (on && (device == null || device == d)) {
                    device = d
                    subscribed = true
                    stopAdvertising()
                    events.log("Phone connected and listening (${d.address})")
                    events.onLinkChanged()
                } else if (!on && d == device) {
                    subscribed = false
                    events.log("Phone stopped listening")
                    events.onLinkChanged()
                }
            }
        }

        override fun onDescriptorReadRequest(d: BluetoothDevice, requestId: Int, offset: Int, desc: BluetoothGattDescriptor) {
            val v = if (subscribed) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, v)
        }

        override fun onCharacteristicReadRequest(d: BluetoothDevice, requestId: Int, offset: Int, c: BluetoothGattCharacteristic) {
            server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
        }

        override fun onNotificationSent(d: BluetoothDevice, status: Int) {
            main.post {
                main.removeCallbacks(sendTimeout)
                sending = false
                pump()
            }
        }
    }

    companion object {
        /** 12 characters with "SB", model code BM21: what an SF 250 dash calls itself. */
        const val NAME = "SBM21SIMDASH"
        val SERVICE: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val WRITE: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        val NOTIFY: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
