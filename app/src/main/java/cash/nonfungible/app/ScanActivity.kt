package cash.nonfungible.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import cash.nonfungible.app.databinding.ActivityScanBinding
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** Reads one QR code with the camera and hands its text back: Numo's scanner, pared down. */
class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private val frames = Executors.newSingleThreadExecutor()
    private val reader = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    )

    @Volatile
    private var found = false

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            look()
        } else {
            Toast.makeText(this, R.string.scan_no_camera, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.back.setOnClickListener { finish() }
        val allowed = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        if (allowed == PackageManager.PERMISSION_GRANTED) look() else askCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        frames.shutdown()
        reader.close()
        super.onDestroy()
    }

    private fun look() {
        val cameras = ProcessCameraProvider.getInstance(this)
        cameras.addListener({
            try {
                val preview = Preview.Builder().build()
                preview.surfaceProvider = binding.camera.surfaceProvider
                val frame = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                frame.setAnalyzer(frames, ::read)
                cameras.get().unbindAll()
                cameras.get().bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, frame)
            } catch (e: ExecutionException) {
                Log.e(TAG, "No camera: ${e.message}")
                finish()
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "No camera to look through: ${e.message}")
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun read(frame: androidx.camera.core.ImageProxy) {
        val picture = frame.image
        if (picture == null || found) return frame.close()
        reader.process(InputImage.fromMediaImage(picture, frame.imageInfo.rotationDegrees))
            .addOnSuccessListener { codes ->
                val text = codes.firstNotNullOfOrNull { it.rawValue }
                if (text != null && !found) {
                    found = true
                    binding.camera.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, text))
                    finish()
                }
            }
            .addOnCompleteListener { frame.close() }
    }

    companion object {
        const val EXTRA_TEXT = "text"
        private const val TAG = "ScanActivity"
    }
}
