package com.joaovictor.ble

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import com.joaovictor.ble.tools.ConnectionEventListener
import com.joaovictor.ble.tools.ConnectionManager
import com.joaovictor.ble.tools.isReadable
import com.joaovictor.ble.tools.printGattTable
import org.jetbrains.anko.support.v4.alert
import org.jetbrains.anko.support.v4.runOnUiThread
import timber.log.Timber
import java.util.*
import kotlin.collections.HashMap


private const val ENABLE_BLUETOOTH_REQUEST_CODE = 1
private const val LOCATION_PERMISSION_REQUEST_CODE = 2


class SecondFragment : Fragment() {

    /*******************************************
     * UI Properties
     *******************************************/

    private lateinit var scanButton : Button
    private lateinit var connectButton : Button
    private lateinit var textView : TextView

    /*******************************************
     * Properties
     *******************************************/

    private val printTextView = MutableLiveData<String>()
    private val bleDevices = HashMap<String, BluetoothDevice>()
    private val beacons = HashMap<String?, Beacon?>()
    private lateinit var bleDevice : BluetoothDevice

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = activity?.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val bleScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner
    }

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    private var isScanning = false
        set(value) {
            field = value

            runOnUiThread { scanButton.text = if (value) "Stop Scan" else "Start Scan" }
        }

    private val scanResults = mutableListOf<ScanResult>()


    private val isLocationPermissionGranted
        get() = requireContext().hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

    /*******************************************
     * Activity function overrides
     *******************************************/

    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_second, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        scanButton = activity?.findViewById(R.id.button_second)!!
        connectButton = activity?.findViewById(R.id.button_connect)!!
        textView = activity?.findViewById(R.id.textview_second)!!

        printTextView.observe(viewLifecycleOwner) {value ->
            textView.text = value
        }

        scanButton.setOnClickListener {

            if (isScanning) {
                Timber.i("Stopping scanning")
                stopBleScan()
                connectButton.isEnabled = true
            } else {
                Timber.i("Start scanning")
                startBleScan()
                connectButton.isEnabled = false
            }
        }

        connectButton.setOnClickListener {
            bleDevices.forEach { (_, beacon) ->
                Timber.i("Looping with ${beacon.address}")

                with(beacon) {
                    connectGatt(context, false, gattCallback)
                }
            }

        }
    }

    override fun onResume() {
        super.onResume()
        ConnectionManager.registerListener(connectionEventListener)
        if (!bluetoothAdapter.isEnabled) {
            promptEnableBluetooth()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            ENABLE_BLUETOOTH_REQUEST_CODE -> {
                if (resultCode != Activity.RESULT_OK) {
                    promptEnableBluetooth()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_DENIED) {
                    requestLocationPermission()
                } else {
                    startBleScan()
                }
            }
        }
    }

    /*******************************************
     * Private functions
     *******************************************/

    private fun promptEnableBluetooth() { //pede para o usuario ativar o BLE
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, ENABLE_BLUETOOTH_REQUEST_CODE)
        }
    }

    private fun startBleScan() { //somente precisamos da permissao quando comecar a escanear
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isLocationPermissionGranted) {
            requestLocationPermission()
        } else {
            scanResults.clear()
            bleScanner.startScan(null, scanSettings, scanCallback)
            isScanning = true
        }
    }

    private fun stopBleScan() {
        bleScanner.stopScan(scanCallback)
        isScanning = false
    }

    private fun requestLocationPermission() {
        if (isLocationPermissionGranted) {
            return
        }
        runOnUiThread {
            alert {
                title = "Location permission required"
                message = "Starting from Android M (6.0), the system requires apps to be granted " +
                        "location access in order to scan for BLE printTextView."
                isCancelable = false
                positiveButton(android.R.string.ok) {
                    activity?.requestPermission(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        LOCATION_PERMISSION_REQUEST_CODE
                    )
                }
            }.show()
        }
    }

    /*******************************************
     * Callback bodies
     *******************************************/

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val indexQuery = scanResults.indexOfFirst { it.device.address == result.device.address }
            if (indexQuery != -1) { // A scan result already exists with the same address
                scanResults[indexQuery] = result

            } else {
                with(result.device) {
                    Timber.i("Found BLE device! Name: ${name ?: "Unnamed"}, address: $address")
                }
                scanResults.add(result)

                printTextView.postValue(printTextView.value + "\n" + result.device.address.toString())

                result.device.also {deviceResult ->
                    if (deviceResult.name == "Kontakt") {
                        bleDevice = deviceResult
                        bleDevices[deviceResult.address] = result.device
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Timber.e("onScanFailed: code $errorCode")
        }
    }

    private val connectionEventListener by lazy {
        ConnectionEventListener().apply {
            onConnectionSetupComplete = { _ ->
                ConnectionManager.unregisterListener(this)
            }
            onDisconnect = {
                runOnUiThread {
                    alert {
                        title = "Disconnected"
                        message = "Disconnected or unable to connect to device."
                        positiveButton("OK") {}
                    }.show()
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        //Gerencia a conexão
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val deviceAddress = gatt?.device?.address

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Timber.w("Successfully connected to $deviceAddress")
                    //TODO: Store a reference to BluetoothGatt
                    gatt?.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Timber.w("Successfully disconnected from $deviceAddress")
                    gatt?.close()
                }
            } else {
                Timber.w("Error $status encountered for $deviceAddress! Disconnecting...")
                gatt?.close()
            }
        }

        //Serviços descobertos
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            with(gatt) {
                Timber.w("Discovered ${this?.services?.size} services for ${this?.device?.address}")
                this?.printGattTable()
                readBatteryLevel(gatt)

            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            with(characteristic) {
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        val readBytes : ByteArray? = this?.value
                        val batteryLevel = readBytes?.first()?.toInt()

                        beacons[gatt?.device?.address] = Beacon(
                                gatt?.device?.address,
                                gatt?.device?.address,
                                gatt?.device?.address,
                                1.0,
                                batteryLevel
                        )

                        Timber.i("Read characteristic ${this?.uuid} with value ${batteryLevel}%")

                        gatt?.disconnect()
                    }
                    BluetoothGatt.GATT_READ_NOT_PERMITTED -> {
                        Timber.i("Read not permitted for ${this?.uuid}!")
                    }
                    else -> {
                        Timber.i("Characteristic read failed for ${this?.uuid}, error: $status")
                    }
                }
            }
        }
    }

    /*******************************************
     * Extension functions
     *******************************************/

    private fun Context.hasPermission(permissionType: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permissionType) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun Activity.requestPermission(permission: String, requestCode: Int) {
        ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
    }

    /*******************************************
     * Usual functions
     *******************************************/

    private fun readBatteryLevel(gatt: BluetoothGatt?) {
        val batteryServiceUuid = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val batteryLevelCharUuid = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val batteryLevelChar = gatt
                ?.getService(batteryServiceUuid)?.getCharacteristic(batteryLevelCharUuid)
        if (batteryLevelChar?.isReadable() == true) {
            gatt.readCharacteristic(batteryLevelChar)
        }
    }
}