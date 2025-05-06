package com.example.visiongo

import android.os.Bundle
import android.content.Intent
import android.app.Activity
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge


class MainActivity : AppCompatActivity() {

    private val CAMERA_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        get_permissions()

        //Find the button in the layout
        val openCameraButton: Button = findViewById(R.id.openCameraButton)

        //Set a click listener to open the camera
        openCameraButton.setOnClickListener {
            openCamera()
        }
    }

    fun get_permissions(){
        var permissionLst = mutableListOf<String>()

        if(checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) permissionsLst.add(android.Manifest.permission.CAMERA)
        if(checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) permissionsLst.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        if(checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) permissionsLst.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)

        if(permissionLst.size > 0){
            requestPermissions(permissionLst.toTypedArray(), 101)
        }

        override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
        ){
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            grantResults.forEach {
                if(it != PackageManager.PERMISSION_GRANTED){
                    get_permissions()
                }
            }
        }
    }

    private fun openCamera() {
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (cameraIntent.resolveActivity(packageManager) != null){
            startActivityForResult(cameraIntent, CAMERA_REQUEST_CODE)
        } else {
            Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAMERA_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            //Handle the captured image here
            val imageBitmap = data?.extras?.get("data")
            Toast.makeText(this, "Image captured successfully!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Camera action canceled", Toast.LENGTH_SHORT).show()
        }
    }
}