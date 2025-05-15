// BluetoothManager.kt

package com.example.visiongo // Use your app's package name

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager as AndroidBluetoothManager // Alias to avoid name clash
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.Manifest
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

// Define interfaces for callbacks or state changes
interface BluetoothConnectionListener {
    val listener: Any

    fun onDeviceFound(device: BluetoothDevice)
    fun onConnectionAttempt(device: BluetoothDevice)
    fun onConnected(socket: BluetoothSocket)
    fun onConnectionFailed(device: BluetoothDevice?, errorMessage: String?)
    fun onDisconnected()
    fun onDataReceived(data: String)
    fun onDataSent(data: String)
    fun onError(errorMessage: String?)
}

// Class to manage Bluetooth operations
class BluetoothManager(private val context: Context, private val listener: MainActivity) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy(LazyThreadSafetyMode.NONE) {
        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as AndroidBluetoothManager
        bluetoothManager.adapter
    }

    // Replace with your service UUID
    private val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var connectJob: Job? = null
    private var manageSocketJob: Job? = null
    private var manageSocketScope: CoroutineScope? = null

    private var connectedSocket: BluetoothSocket? = null
    private var mmInStream: InputStream? = null
    private var mmOutStream: OutputStream? = null

    // Check if Bluetooth is supported and enabled
    fun isBluetoothAvailable(): Boolean {
        return bluetoothAdapter != null && bluetoothAdapter!!.isEnabled
    }

    // Request necessary Bluetooth permissions
    // You would typically handle this in your Activity before using Bluetooth features
    // @SuppressLint("MissingPermission") // Suppress this warning if permissions are handled elsewhere
    @RequiresApi(Build.VERSION_CODES.S)
    fun hasBluetoothConnectPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT // Replace with the actual permission name
        ) == PackageManager.PERMISSION_GRANTED
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission") // Suppress the permission warning as we check it
    fun getPairedDevices(): Set<BluetoothDevice>? {
        // 1. Check if Bluetooth is available
        if (bluetoothAdapter == null) {
            Log.w("BluetoothManager", "Bluetooth is not available on this device.")
            listener.onError("Bluetooth is not available.")
            return null
        }

        // 2. Check for necessary permissions
        if (!hasBluetoothPermission() || !hasBluetoothConnectPermission()) {
            val errorMessage =
                "Required permissions (BLUETOOTH, BLUETOOTH_CONNECT) not granted."
            Log.e("BluetoothManager", errorMessage)
            listener.onError(errorMessage)
            return null
        }

        // 3. Get the set of bonded devices
        return try {
            bluetoothAdapter!!.bondedDevices // Requires BLUETOOTH_CONNECT on API 31+
        } catch (e: SecurityException) {
            // This catch might be redundant if hasBluetoothConnectPermission() is accurate,
            // but it's a good safeguard.
            Log.e("BluetoothManager", "SecurityException getting paired devices: ${e.message}", e)
            listener.onError("Permission denied to get paired devices: ${e.message}")
            null
        } catch (e: Exception) {
            // Catch any other unexpected exceptions
            Log.e("BluetoothManager", "Error getting paired devices: ${e.message}", e)
            listener.onError("Error getting paired devices: ${e.message}")
            null
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH // Replace with the actual permission name
        ) == PackageManager.PERMISSION_GRANTED
    }

    // Example function to connect to a paired device
    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission") // Requires BLUETOOTH_CONNECT permission
    fun connectToDevice(device: BluetoothDevice) {
        if (!hasBluetoothConnectPermission()) {
            listener.onError("Bluetooth CONNECT permission not granted.")
            return
        }

        if (bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter!!.cancelDiscovery()
        }

        listener.onConnectionAttempt(device)

        connectJob = CoroutineScope(Dispatchers.IO).launch {
            var tempSocket: BluetoothSocket? = null
            try {
                tempSocket = device.createRfcommSocketToServiceRecord(MY_UUID)
                tempSocket.connect() // Blocking call

                connectedSocket = tempSocket
                listener.onConnected(connectedSocket!!) // Notify listener

                // Start managing the connected socket
                startManagingConnectedSocket(connectedSocket!!)

            } catch (e: IOException) {
                Log.e("BluetoothManager", "Connection failed", e)
                try {
                    tempSocket?.close()
                } catch (closeException: IOException) {
                    Log.e(
                        "BluetoothManager",
                        "Could not close socket after connection failure",
                        closeException
                    )
                }
                listener.onConnectionFailed(device, e.message) // Notify listener
            }
        }
    }

    // Coroutine to manage the connected socket (read/write)
    private fun startManagingConnectedSocket(socket: BluetoothSocket) {
        // Create a new scope for managing the socket
        manageSocketScope = CoroutineScope(Dispatchers.IO)

        manageSocketJob = manageSocketScope?.launch { // Launch the job within the scope
            try {
                mmInStream = socket.inputStream
                mmOutStream = socket.outputStream

                // Launch the reading coroutine within the same scope
                launch { readDataFromStream() }

                // You can add a writing coroutine or function here as well
                // launch { writeDataToStream() }

                // Keep the scope/job active until cancelled
                this.coroutineContext[Job]?.join()

            } catch (e: IOException) {
                Log.e("BluetoothManager", "Error getting streams", e)
                listener.onError("Error getting data streams: ${e.message}")
                disconnect() // Disconnect on stream error
            } finally {
                // Clean up the scope when the managing job finishes
                manageSocketScope = null
            }
        }
    }

    // Coroutine function to read data
    private suspend fun readDataFromStream() {
        val inputStream = mmInStream ?: return // Ensure stream is not null
        val buffer = ByteArray(1024)
        var bytes: Int

        try {
            while (coroutineContext.isActive) {
                bytes = inputStream.read(buffer) // Blocking read
                if (bytes > 0) {
                    val readMessage = String(buffer, 0, bytes, StandardCharsets.UTF_8)
                    Log.d("BluetoothManager", "Received: $readMessage")
                    withContext(Dispatchers.Main) {
                        listener.onDataReceived(readMessage) // Notify listener on main thread
                    }
                }
            }
        } catch (e: IOException) {
            Log.d("BluetoothManager", "Input stream disconnected", e)
            withContext(Dispatchers.Main) {
                listener.onError("Connection lost: ${e.message}")
            }
            disconnect() // Disconnect on read error
        } catch (e: CancellationException) {
            Log.d("BluetoothManager", "Read coroutine cancelled", e)
            withContext(Dispatchers.Main) {
                listener.onDisconnected() // Notify listener on main thread
            }
        } finally {
            // Streams and socket are closed in the disconnect function
            Log.d("BluetoothManager", "Read coroutine finished.")
        }
    }

    // Function to send data
    fun sendData(bytes: ByteArray) {
        manageSocketJob?.launch(Dispatchers.IO) {
            val outputStream = mmOutStream ?: run {
                Log.e("BluetoothManager", "Output stream is null, cannot send.")
                listener.onError("Cannot send data: Output stream not available.")
                return@launch
            }
            try {
                outputStream.write(bytes)
                Log.d("BluetoothManager", "Sent: ${String(bytes)}")
                withContext(Dispatchers.Main) {
                    listener.onDataSent(String(bytes))
                }
            } catch (e: IOException) {
                Log.e("BluetoothManager", "Error during write", e)
                withContext(Dispatchers.Main) {
                    listener.onError("Error sending data: ${e.message}")
                }
                disconnect() // Disconnect on write error
            }
        }?: run {
            // Handle the case where the scope is null (not connected)
            Log.e("BluetoothManager", "Cannot send data: Bluetooth connection not active.")
            listener.onError("Cannot send data: Bluetooth connection not active.")
        }
    }


    // Disconnect the Bluetooth connection
    fun disconnect() {
        Log.d("BluetoothManager", "Attempting to disconnect...")

        // 1. Cancel any ongoing connection attempt and managing coroutine(s)
        // Canceling coroutines is a non-blocking operation.
        connectJob?.cancel()
        manageSocketJob?.cancel()
        // Also cancel the management scope if you introduced one in startManagingConnectedSocket
        manageSocketScope?.cancel() // If you added this as suggested previously

        // 2. Perform blocking close operations on a background thread
        // Use a separate CoroutineScope or runBlocking (carefully) for cleanup
        // A dedicated cleanup scope is often a good approach
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Closing streams and sockets can be blocking operations.
                // Perform these on the IO dispatcher.
                mmInStream?.close()
                mmOutStream?.close()
                connectedSocket?.close()

                Log.d("BluetoothManager", "Bluetooth streams and socket closed.")

            } catch (e: IOException) {
                Log.e("BluetoothManager", "Error closing Bluetooth streams or socket", e)
                // Notify listener on the main thread about the error
                withContext(Dispatchers.Main) {
                    listener.onError("Error during disconnection: ${e.message}")
                }
            } finally {
                // 3. Update state and notify listener on the main thread
                // Ensure these happen regardless of whether closing succeeded or failed
                mmInStream = null
                mmOutStream = null
                connectedSocket = null
                manageSocketScope = null // Clean up the scope reference

                Log.d("BluetoothManager", "Bluetooth connection state reset.")

                // Notify listener on the main thread about disconnection
                withContext(Dispatchers.Main) {
                    listener.onDisconnected()
                }
            }
        }

        // The disconnect function itself returns immediately after launching the cleanup coroutine.
        Log.d("BluetoothManager", "Disconnect function finished (cleanup launched in background).")
    }
}

private fun CoroutineContext?.launch(
    context: CoroutineDispatcher,
    block: suspend CoroutineScope.() -> Unit
): Job? {
        TODO("Not yet implemented")
}
