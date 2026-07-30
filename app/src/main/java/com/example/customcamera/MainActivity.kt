package com.example.customcamera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var btnCapture: View
    private lateinit var txtStatus: TextView
    private lateinit var shutterFlash: View

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        btnCapture = findViewById(R.id.btnCapture)
        txtStatus = findViewById(R.id.txtStatus)
        shutterFlash = findViewById(R.id.shutterFlash)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        btnCapture.setOnClickListener {
            // ✨ IPHONE SCREEN FLASH EFFECT ✨
            shutterFlash.alpha = 1f
            shutterFlash.animate().alpha(0f).setDuration(200).start()
            
            takeAndProcessPhoto()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val extensionsManagerFuture = ExtensionsManager.getInstanceAsync(this, cameraProvider)
            
            extensionsManagerFuture.addListener({
                val extensionsManager = extensionsManagerFuture.get()
                var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                if (extensionsManager.isExtensionAvailable(cameraSelector, ExtensionMode.AUTO)) {
                    cameraSelector = extensionsManager.getExtensionEnabledCameraSelector(cameraSelector, ExtensionMode.AUTO)
                }

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                    // Hiding the text by default for a clean look
                    txtStatus.text = ""
                } catch (exc: Exception) {
                    Log.e("iPhoneCamera", "Binding failed", exc)
                }

            }, ContextCompat.getMainExecutor(this))

        }, ContextCompat.getMainExecutor(this))
    }

    private fun takeAndProcessPhoto() {
        val imageCapture = imageCapture ?: return
        txtStatus.text = "Processing..."

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    super.onCaptureSuccess(image)
                    val bitmap = image.toBitmap()
                    image.close()

                    cameraExecutor.execute {
                        val processedBitmap = applyProPhotonicEngine(bitmap)
                        saveImageToGallery(processedBitmap)

                        runOnUiThread {
                            txtStatus.text = "" // Clear status when done
                            Toast.makeText(applicationContext, "Photo Saved!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("iPhoneCamera", "Capture failed: ${exception.message}", exception)
                    txtStatus.text = "Failed"
                }
            }
        )
    }

    private fun applyProPhotonicEngine(input: Bitmap): Bitmap {
        val contrast = 1.15f 
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val contrastMatrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))

        val colorMatrix = ColorMatrix(floatArrayOf(
            1.08f, 0.00f, 0.00f, 0.0f, 5.0f,  
            0.00f, 1.05f, 0.00f, 0.0f, 2.0f,  
            0.00f, 0.00f, 0.95f, 0.0f, 0.0f,  
            0.00f, 0.00f, 0.00f, 1.0f, 0.0f   
        ))

        contrastMatrix.postConcat(colorMatrix)

        val paint = Paint()
        paint.colorFilter = ColorMatrixColorFilter(contrastMatrix)

        val output = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmap(input, 0f, 0f, paint)
        return output
    }

    private fun saveImageToGallery(bitmap: Bitmap) {
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "APPLE_$name.jpg") 
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/iPhoneCamera")
            }
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        uri?.let {
            val stream: OutputStream? = resolver.openOutputStream(it)
            stream?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

