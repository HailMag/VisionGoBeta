package com.example.visiongo

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.Toast
import android.widget.TextView
import android.bluetooth.BluetoothSocket
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Locale
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream



class MainActivity : AppCompatActivity(), BluetoothConnectionListener {

    private lateinit var bluetoothManager: com.example.visiongo.BluetoothManager
    override lateinit var listener: BluetoothConnectionListener

    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var previewView: PreviewView // Declare PreviewView
    private lateinit var captureButton: Button
    private var imageCapture: ImageCapture? = null
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var sendButton: Button
    private lateinit var bluetoothStatusTextView: TextView
    private lateinit var bluetoothDataTextView: TextView

    //===========================================================================================================================
    // ActivityResultLauncher for requesting multiple permissions
    //===========================================================================================================================
    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val grantedPermissions = mutableListOf<String>()
            val deniedPermissions = mutableListOf<String>()

            for ((permission, isGranted) in permissions) {
                if (isGranted) {
                    grantedPermissions.add(permission)
                } else {
                    deniedPermissions.add(permission)
                }
            }

            // Now you have lists of granted and denied permissions
            if (deniedPermissions.isNotEmpty()) {
                Log.d("PermissionsResult", "Denied Permissions: $deniedPermissions")

                // Check if any of the denied permissions should show a rationale
                val shouldShowRationale = deniedPermissions.any {
                    shouldShowRequestPermissionRationale(it)
                }

                if (shouldShowRationale) {
                    // Show a rationale explaining why permissions are needed, then request denied permissions again
                    showPermissionRationale(deniedPermissions)
                } else {
                    startCamera()
                    // Permissions were denied (and potentially "Don't ask again" was checked)
                    // Inform the user and possibly guide them to app settings
                    Toast.makeText(
                        this,
                        "The following permissions were denied: ${deniedPermissions.joinToString()}. Some features may be disabled.",
                        Toast.LENGTH_LONG
                    ).show()
                    // Consider providing a way for the user to go to settings.
                }

            } else {
                Log.d("PermissionsResult", "All permissions granted.")
                // Proceed with your feature
                startCamera()
            }
        }
    //===========================================================================================================================

    //===========================================================================================================================
    // Your existing permission request function
    //===========================================================================================================================
    private fun requestCameraAndStoragePermissions() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_MEDIA_IMAGES // Needed if you want to access other apps' images
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10 (API 29) to Android 12 (API 32)
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE // Needed if you want to access other apps' media
            )
        } else {
            // Android 9 (API 28) and lower
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE // Needed for reading other apps' media
                // WRITE_EXTERNAL_STORAGE is needed here if you are NOT using MediaStore for writing
                // but using MediaStore is recommended
            )
        }

        // Filter out null permissions if the else block wasn't fully implemented
        val nonNullPermissions = requiredPermissions.filterNotNull().toTypedArray()

        // Check if permissions are already granted
        val arePermissionsGranted = nonNullPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (arePermissionsGranted) {
            // Permissions are already granted, initialize the camera
            startCamera()
        } else {
            // Request permissions from the user
            requestPermissionsLauncher.launch(nonNullPermissions)
        }
    }
    //===========================================================================================================================

    //===========================================================================================================================
    // Function to show a rationale (Example)
    //===========================================================================================================================
    private fun showPermissionRationale(deniedPermissions: List<String>) {
        // You would typically show a dialog or a custom UI element here
        // explaining why you need the denied permissions.

        // For demonstration purposes, we'll just log and then request again.
        Log.d("PermissionsRationale", "Showing rationale for: $deniedPermissions")

        // After the user acknowledges the rationale, request the denied permissions again
        // This assumes your UI element has a button to proceed.
        // For this example, we'll just immediately re-request the denied permissions.
        requestPermissionsLauncher.launch(deniedPermissions.toTypedArray())
    }
    //===========================================================================================================================

    //===========================================================================================================================
    //
    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Find the PreviewView in your layout
        previewView =
            findViewById(R.id.previewView) // Make sure you have a PreviewView with this ID in your layout file
        captureButton = findViewById(R.id.captureButton)
        bluetoothDataTextView =
            findViewById(R.id.bluetoothDataTextView) // Get a reference to the TextView

        captureButton.setOnClickListener {
            Toast.makeText(this, "Capture button clicked!", Toast.LENGTH_SHORT).show()
            captureImage()
        }


        // Request permissions first
        requestCameraAndStoragePermissions()


        // Initialize UI elements (replace with your actual IDs)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        sendButton = findViewById(R.id.sendButton)
        bluetoothStatusTextView = findViewById(R.id.bluetoothStatusTextView)
        bluetoothDataTextView = findViewById(R.id.bluetoothDataTextView)

        // 1. Instantiate the BluetoothManager
        // Pass 'this' as the Context and 'this' as the BluetoothConnectionListener
        bluetoothManager =
            com.example.visiongo.BluetoothManager(this, this) // Use your package name

        // Example: Set up click listeners to call BluetoothManager functions
        connectButton.setOnClickListener {
            // You need to get the BluetoothDevice you want to connect to here.
            // This typically involves scanning for devices or selecting from paired devices.
            // For demonstration, let's assume you have a paired device already.
            val pairedDevices: Set<BluetoothDevice>? =
                bluetoothManager.getPairedDevices() // Assuming you add this function to BluetoothManager

            val targetDevice: BluetoothDevice? = pairedDevices?.firstOrNull { device ->
                // Replace with the name or address of the device you want to connect to
                device.name == "ESP32"
            }

            if (targetDevice != null) {
                // 3. Call the connectToDevice function
                bluetoothManager.connectToDevice(targetDevice)
                bluetoothStatusTextView.text = "Attempting to connect..."
            } else {
                bluetoothStatusTextView.text = "Target device not found (make sure it's paired)."
                Log.w("MainActivity", "Target Bluetooth device not found among paired devices.")
                // Handle case where the target device is not paired or found
            }
        }

        disconnectButton.setOnClickListener {
            // 3. Call the disconnect function
            bluetoothManager.disconnect()
            bluetoothStatusTextView.text = "Disconnected."
        }

        sendButton.setOnClickListener {
            // 3. Call the sendData function
            val dataToSend = "Hello from Android!"
            val bytes = dataToSend.toByteArray(Charsets.UTF_8)
            bluetoothManager.sendData(bytes)
            bluetoothStatusTextView.text = "Sending data..."
        }

        // Check if Bluetooth is available on startup
        if (bluetoothManager.isBluetoothAvailable()) {
            bluetoothStatusTextView.text = "Bluetooth available."
        } else {
            bluetoothStatusTextView.text = "Bluetooth not available."
            // Disable Bluetooth related UI elements if not available
            connectButton.isEnabled = false
            disconnectButton.isEnabled = false
            sendButton.isEnabled = false
        }

        // ... your other onCreate code ...
    }


    //===========================================================================================================================

    //===========================================================================================================================
    // BlueTooth
    //===========================================================================================================================
    // Implement the BluetoothConnectionListener interface methods
    @SuppressLint("MissingPermission")
    override fun onDeviceFound(device: BluetoothDevice) {
        // Handle scanned devices if you implement scanning
        Log.d("MainActivity", "Device found: ${device.name} - ${device.address}")
        // Update your UI to show discovered devices
    }

    @SuppressLint("MissingPermission")
    override fun onConnectionAttempt(device: BluetoothDevice) {
        Log.d("MainActivity", "Attempting connection to: ${device.name}")
        bluetoothStatusTextView.text = "Connecting to ${device.name}..."
    }

    override fun onConnected(socket: BluetoothSocket) {
        Log.d("MainActivity", "Successfully connected.")
        bluetoothStatusTextView.text = "Connected!"
        // Enable send/disconnect buttons, disable connect button
        connectButton.isEnabled = false
        disconnectButton.isEnabled = true
        sendButton.isEnabled = true
    }

    override fun onConnectionFailed(device: BluetoothDevice?, errorMessage: String?) {
        Log.e("MainActivity", "Connection failed: $errorMessage")
        bluetoothStatusTextView.text = "Connection Failed: $errorMessage"
        // Update UI (enable connect, disable send/disconnect)
        connectButton.isEnabled = true
        disconnectButton.isEnabled = false
        sendButton.isEnabled = false
    }

    override fun onDisconnected() {
        Log.d("MainActivity", "Disconnected.")
        bluetoothStatusTextView.text = "Disconnected."
        // Update UI (enable connect, disable send/disconnect)
        connectButton.isEnabled = true
        disconnectButton.isEnabled = false
        sendButton.isEnabled = false
        bluetoothDataTextView.append("--- Disconnected ---\n") // Indicate disconnection in data log
    }

    override fun onDataReceived(data: String) {
        Log.d("MainActivity", "Data Received: $data")
        // Append received data to the TextView
        bluetoothDataTextView.append(data + "\n")
        // Optional: Auto-scroll
        val scrollY = bluetoothDataTextView.layout?.getLineTop(bluetoothDataTextView.lineCount) ?: 0
        val height = bluetoothDataTextView.height
        val scrollAmount = scrollY - height
        if (scrollAmount > 0) {
            bluetoothDataTextView.scrollTo(0, scrollAmount)
        } else {
            bluetoothDataTextView.scrollTo(0, 0)
        }
    }

    override fun onDataSent(data: String) {
        Log.d("MainActivity", "Data Sent: $data")
        // Optional: Update UI to confirm data sent
        // bluetoothStatusTextView.text = "Data sent: $data" // Might be too verbose
    }

    override fun onError(errorMessage: String?) {
        Log.e("MainActivity", "Bluetooth Error: $errorMessage")
        bluetoothStatusTextView.text = "Error: $errorMessage"
        // Consider what UI state you want in case of an error
    }


    //===========================================================================================================================

    private fun captureImage() {
        // Get a reference to the ImageCapture use case
        val imageCapture = imageCapture ?: run {
            Log.e("ImageCapture", "Image capture use case is not initialized.")
            return
        }

        // Create a timestamped file name for the image
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "Pictures/VisionGo"
                ) // Save to Pictures/CameraX-Images
            }
        }

        // Create output options to specify how and where the captured image is saved
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        // Set up image capture event listener, which is triggered after the photo has been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this), // Executor to run the callback on
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e("ImageCapture", "Photo capture failed: ${exc.message}", exc)
                    Toast.makeText(
                        baseContext,
                        "Photo capture failed: ${exc.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri
                    val msg = "Photo capture succeeded: $savedUri"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d("ImageCapture", msg)
                }
            }
        )
    }


    private fun startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({

            val cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {
        // Create a Preview use case
        val preview = Preview.Builder()
            .build()
            .also {
                it.surfaceProvider = previewView.surfaceProvider
            }

        // Create an ImageCapture use case
        imageCapture = ImageCapture.Builder()
            .build()

        // Select back camera as a default
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            // Unbind all use cases before rebinding
            cameraProvider.unbindAll()

            // Bind use cases to camera
            cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageCapture // Add the ImageCapture use case here
            )

        } catch (exc: Exception) {
            // Handle errors, e.g., camera unavailable
            Toast.makeText(this, "Error starting camera: ${exc.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // You'll likely want to stop camera use cases when the activity is paused or destroyed
    override fun onPause() {
        super.onPause()
        // Unbind use cases to release the camera if cameraProviderFuture is initialized
        if (::cameraProviderFuture.isInitialized) {
            try {
                // Get the cameraProvider and unbind all use cases
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
            } catch (e: Exception) {
                // Handle potential exceptions during getting the provider or unbinding
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        Log.d("MainActivity", "onDestroy called")

        // 1. Bluetooth cleanup code
        Log.d("MainActivity", "Calling bluetoothManager.disconnect()")
        bluetoothManager.disconnect() // Clean up Bluetooth resources

        // 2. CameraX cleanup code
        Log.d("MainActivity", "Unbinding CameraX use cases")
        // Ensure camera is released if cameraProviderFuture is initialized
        if (::cameraProviderFuture.isInitialized) {
            try {
                // Get the cameraProvider and unbind all use cases
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
                Log.d("MainActivity", "CameraX use cases unbound.")
            } catch (e: Exception) {
                // Handle potential exceptions during getting the provider or unbinding
                Log.e("MainActivity", "Error unbinding CameraX use cases in onDestroy", e)
            }
        } else {
            Log.d(
                "MainActivity",
                "cameraProviderFuture not initialized in onDestroy, no CameraX unbinding needed."
            )
        }

        // ... any other cleanup you need to do ...
        Log.d("MainActivity", "onDestroy finished.")
    }


// Define a message handler (e.g., using a Handler or Kotlin Coroutines)
// to process incoming data and update the UI safely.
// For simplicity, this example just logs the data.
// In a real app, you would pass a Handler or a mechanism to
// communicate back to the main thread.

    private class ConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {

        private val mmInStream: InputStream = mmSocket.inputStream
        private val mmOutStream: OutputStream = mmSocket.outputStream // For sending data
        private val mmBuffer: ByteArray = ByteArray(1024) // Buffer for incoming data

        override fun run() {
            var numBytes: Int // bytes returned from read()

            // Keep listening to the InputStream until an exception occurs.
            while (true) {
                try {
                    // Read from the InputStream.
                    numBytes = mmInStream.read(mmBuffer)
                    // Use the bytes from the buffer.
                    val readMessage = String(mmBuffer, 0, numBytes)
                    Log.d("BluetoothData", "Received: $readMessage")

                    // Process the received data (e.g., update UI, parse data)
                    // You would typically send this data to the main thread
                    // using a Handler or other communication mechanism.

                } catch (e: IOException) {
                    Log.d("BluetoothData", "Input stream was disconnected", e)
                    // Handle the case where the connection is lost
                    break
                }
            }
        }

        // Call this from the main activity to send data to the remote device.
        fun write(bytes: ByteArray) {
            try {
                mmOutStream.write(bytes)
                // Optionally log or confirm data was sent
                Log.d("BluetoothData", "Sent: ${String(bytes)}")
            } catch (e: IOException) {
                Log.e("BluetoothData", "Error during write", e)
                // Handle the error during writing
            }
        }

        // Call this method from the main activity to shut down the connection.
        fun cancel() {
            try {
                mmSocket.close()
                Log.d("BluetoothData", "Socket closed")
            } catch (e: IOException) {
                Log.e("BluetoothData", "Could not close the connect socket", e)
            }
        }
    }

    // Modify manageConnectedSocket to start the ConnectedThread
    private fun manageConnectedSocket(socket: BluetoothSocket) {
        // Start the thread to manage the connected socket and handle data transfer
        ConnectedThread(socket).start()
    }
}