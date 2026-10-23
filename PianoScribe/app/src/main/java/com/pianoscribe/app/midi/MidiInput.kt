package com.pianoscribe.app.midi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class MidiNoteEvent(val on: Boolean, val key: Int, val velocity: Int)

/** A MIDI source the user can connect: an attached/virtual device or a scanned BLE keyboard. */
data class MidiSource(val id: String, val name: String, val bluetooth: Boolean, internal val info: MidiDeviceInfo? = null, internal val ble: BluetoothDevice? = null)

/**
 * Reads notes from USB and Bluetooth LE MIDI keyboards via android.media.midi.
 */
class MidiInput(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager: MidiManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) context.getSystemService(Context.MIDI_SERVICE) as? MidiManager else null

    val available: Boolean get() = manager != null

    private val _events = MutableSharedFlow<MidiNoteEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<MidiNoteEvent> = _events

    private val _sources = MutableStateFlow<List<MidiSource>>(emptyList())
    val sources: StateFlow<List<MidiSource>> = _sources

    private val _connected = MutableStateFlow<String?>(null)
    /** Name of the connected device, or null. */
    val connected: StateFlow<String?> = _connected

    private val _held = MutableStateFlow<Set<Int>>(emptySet())
    /** Keys currently held down on the keyboard. */
    val held: StateFlow<Set<Int>> = _held

    private var device: MidiDevice? = null
    private var port: MidiOutputPort? = null
    private val bleFound = LinkedHashMap<String, MidiSource>()
    private var scanning = false

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = refresh()
        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            if (_connected.value != null && this@MidiInput.device?.info?.id == device.id) disconnect()
            refresh()
        }
    }

    init {
        @Suppress("DEPRECATION")
        manager?.registerDeviceCallback(callback, handler)
        refresh()
    }

    @Suppress("DEPRECATION")
    fun refresh() {
        val m = manager ?: return
        val attached = m.devices.filter { it.outputPortCount > 0 }.map { info ->
            val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
                ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI device ${info.id}"
            MidiSource("dev:${info.id}", name, info.type == MidiDeviceInfo.TYPE_BLUETOOTH, info = info)
        }
        _sources.value = attached + bleFound.values.filter { b -> attached.none { it.name == b.name } }
    }

    /** Scans 10 s for BLE MIDI keyboards. Needs BLUETOOTH_SCAN (12+) or location (8-11). */
    @SuppressLint("MissingPermission")
    fun scanBluetooth(): Boolean {
        val bt = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return false
        if (!bt.isEnabled) return false
        val scanner = bt.bluetoothLeScanner ?: return false
        if (scanning) return true
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                val name = runCatching { d.name }.getOrNull() ?: result.scanRecord?.deviceName ?: d.address
                bleFound[d.address] = MidiSource("ble:${d.address}", name, true, ble = d)
                refresh()
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(BLE_MIDI_SERVICE)).build()
        return runCatching {
            scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            scanning = true
            handler.postDelayed({ runCatching { scanner.stopScan(cb) }; scanning = false }, 10_000)
            true
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    fun connect(source: MidiSource) {
        val m = manager ?: return
        disconnect()
        val listener = MidiManager.OnDeviceOpenedListener { dev ->
            if (dev == null) return@OnDeviceOpenedListener
            device = dev
            val info = dev.info
            val portIndex = (0 until info.outputPortCount).firstOrNull() ?: return@OnDeviceOpenedListener
            port = dev.openOutputPort(portIndex)?.also { it.connect(receiver) }
            _connected.value = source.name
        }
        when {
            source.info != null -> m.openDevice(source.info, listener, handler)
            source.ble != null -> m.openBluetoothDevice(source.ble, listener, handler)
        }
    }

    fun disconnect() {
        runCatching { port?.disconnect(receiver) }
        runCatching { port?.close() }
        runCatching { device?.close() }
        port = null
        device = null
        _connected.value = null
        _held.value = emptySet()
    }

    /** Parses raw MIDI bytes (with running status) into note events. */
    private val receiver = object : MidiReceiver() {
        private var running = 0
        private val data = IntArray(2)
        private var count = 0

        override fun onSend(msg: ByteArray, offset: Int, count0: Int, timestamp: Long) {
            for (i in offset until offset + count0) {
                val b = msg[i].toInt() and 0xFF
                if (b >= 0xF8) continue // real-time
                if (b >= 0x80) { running = if (b < 0xF0) b else 0; count = 0; continue }
                if (running == 0) continue
                data[count++] = b
                val type = running and 0xF0
                val needed = if (type == 0xC0 || type == 0xD0) 1 else 2
                if (count < needed) continue
                count = 0
                when (type) {
                    0x90 -> emit(data[1] > 0, data[0], data[1])
                    0x80 -> emit(false, data[0], 0)
                }
            }
        }

        private fun emit(on: Boolean, key: Int, vel: Int) {
            _held.value = if (on) _held.value + key else _held.value - key
            _events.tryEmit(MidiNoteEvent(on, key, vel))
        }
    }

    companion object {
        const val BLE_MIDI_SERVICE = "03B80E5A-EDE8-4B33-A751-6CE34EC4C700"
    }
}
