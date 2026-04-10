package com.timecoin.wallet.ui.screen

import android.Manifest
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
    onResult: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember { mutableStateOf(false) }
    var flashEnabled by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan QR Code") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        flashEnabled = !flashEnabled
                        camera?.cameraControl?.enableTorch(flashEnabled)
                    }) {
                        Icon(
                            if (flashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Toggle Flash",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.7f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        if (!hasCameraPermission) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Camera permission is required to scan QR codes.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant Permission")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onBack) {
                    Text("Go Back")
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

                DisposableEffect(Unit) {
                    onDispose { cameraExecutor.shutdown() }
                }

                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }

                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            try {
                                val cameraProvider = cameraProviderFuture.get()

                                val preview = Preview.Builder().build().also {
                                    it.surfaceProvider = previewView.surfaceProvider
                                }

                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                    .also { analysis ->
                                        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                            try {
                                                val yPlane    = imageProxy.planes[0]
                                                val width     = imageProxy.width
                                                val height    = imageProxy.height
                                                val rowStride = yPlane.rowStride
                                                val buffer    = yPlane.buffer

                                                buffer.rewind()
                                                val bytes = ByteArray(width * height)
                                                for (row in 0 until height) {
                                                    buffer.position(row * rowStride)
                                                    buffer.get(bytes, row * width, width)
                                                }

                                                val hints = mapOf(
                                                    DecodeHintType.POSSIBLE_FORMATS to listOf(
                                                        BarcodeFormat.QR_CODE,
                                                        BarcodeFormat.DATA_MATRIX,
                                                    ),
                                                    DecodeHintType.TRY_HARDER to true,
                                                )

                                                var decodedText: String? = null
                                                for (mirror in listOf(false, true)) {
                                                    if (decodedText != null) break
                                                    try {
                                                        val src = PlanarYUVLuminanceSource(
                                                            bytes, width, height, 0, 0, width, height, mirror,
                                                        )
                                                        decodedText = MultiFormatReader()
                                                            .apply { setHints(hints) }
                                                            .decode(BinaryBitmap(HybridBinarizer(src)))
                                                            .text
                                                    } catch (_: NotFoundException) { }
                                                }

                                                if (decodedText != null) {
                                                    Log.d("QrScanner", "Scanned: $decodedText")
                                                    try {
                                                        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
                                                            .startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                                                    } catch (_: Exception) { }
                                                    Handler(Looper.getMainLooper()).post {
                                                        onResult(decodedText!!)
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                Log.w("QrScanner", "Frame error", e)
                                            } finally {
                                                imageProxy.close()
                                            }
                                        }
                                    }

                                cameraProvider.unbindAll()
                                camera = cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageAnalysis,
                                )
                            } catch (e: Exception) {
                                Log.e("QrScanner", "Camera setup failed", e)
                            }
                        }, ContextCompat.getMainExecutor(ctx))

                        previewView
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                // Viewfinder overlay
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val scanBoxSize = size.minDimension * 0.65f
                    val left = (size.width - scanBoxSize) / 2
                    val top  = (size.height - scanBoxSize) / 2

                    drawRect(Color.Black.copy(alpha = 0.5f))

                    drawRoundRect(
                        color = Color.Transparent,
                        topLeft = Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(scanBoxSize, scanBoxSize),
                        cornerRadius = CornerRadius(16f, 16f),
                        blendMode = BlendMode.Clear,
                    )

                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(scanBoxSize, scanBoxSize),
                        cornerRadius = CornerRadius(16f, 16f),
                        style = Stroke(width = 3f),
                    )
                }

                Text(
                    text = "Point camera at a QR code",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 64.dp),
                )
            }
        }
    }
}
