
package com.automotor.matriculasocr

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var resultText: TextView
    private lateinit var captureButton: Button
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var recognizedPlate = ""

    private val cameraPermission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) startCamera()
            else resultText.text = "Necesitamos permiso para usar la cámara"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            gravity = Gravity.CENTER
        }

        val title = TextView(this).apply {
            text = "MATRÍCULAS OCR"
            textSize = 25f
            gravity = Gravity.CENTER
        }

        previewView = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        resultText = TextView(this).apply {
            text = "Enfoca una matrícula y pulsa FOTOGRAFIAR"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(10, 20, 10, 20)
        }

        captureButton = Button(this).apply {
            text = "FOTOGRAFIAR MATRÍCULA"
            setOnClickListener { takePhoto() }
        }

        val copyButton = Button(this).apply {
            text = "COPIAR MATRÍCULA"
            setOnClickListener {
                if (recognizedPlate.isNotEmpty()) {
                    val clipboard =
                        getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("Matrícula", recognizedPlate)
                    )
                    resultText.text = "$recognizedPlate\n¡Copiada!"
                }
            }
        }

        layout.addView(title)
        layout.addView(previewView)
        layout.addView(resultText)
        layout.addView(captureButton)
        layout.addView(copyButton)

        setContentView(layout)

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)

        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            imageCapture = ImageCapture.Builder().build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                resultText.text = "Error al iniciar la cámara"
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return

        captureButton.isEnabled = false
        resultText.text = "Analizando matrícula..."

        val file = File(cacheDir, "matricula.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {

                override fun onImageSaved(
                    output: ImageCapture.OutputFileResults
                ) {
                    recognizeText(file)
                }

                override fun onError(exception: ImageCaptureException) {
                    resultText.text = "Error al fotografiar"
                    captureButton.isEnabled = true
                }
            }
        )
    }

    private fun recognizeText(file: File) {
        try {
            val image = InputImage.fromFilePath(
                this, android.net.Uri.fromFile(file)
            )

            val recognizer = TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS
            )

            recognizer.process(image)
                .addOnSuccessListener { visionText ->

                    val raw = visionText.text
                        .uppercase()
                        .replace(" ", "")
                        .replace("-", "")
                        .replace("\n", "")

                    val pattern =
                        Regex("[0-9]{4}[BCDFGHJKLMNPRSTVWXYZ]{3}")

                    val match = pattern.find(raw)

                    recognizedPlate = match?.value ?: ""

                    resultText.text =
                        if (recognizedPlate.isNotEmpty()) {
                            "MATRÍCULA DETECTADA:\n$recognizedPlate"
                        } else {
                            "No he detectado una matrícula.\nTexto: ${visionText.text}"
                        }

                    captureButton.isEnabled = true
                    recognizer.close()
                }
                .addOnFailureListener {
                    resultText.text = "Error en el reconocimiento OCR"
                    captureButton.isEnabled = true
                    recognizer.close()
                }

        } catch (e: Exception) {
            resultText.text = "No se pudo analizar la fotografía"
            captureButton.isEnabled = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
