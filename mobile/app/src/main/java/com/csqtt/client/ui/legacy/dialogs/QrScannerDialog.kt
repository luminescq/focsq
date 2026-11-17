package com.csqtt.client.ui.legacy.dialogs

import android.view.ScaleGestureDetector
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.detector.Detector
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

@Composable
fun QrScannerDialog(
    onDismissRequest: () -> Unit,
    onQrCodeScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var scanned by remember { mutableStateOf(false) }
    var activeCamera by remember { mutableStateOf<Camera?>(null) }
    var currentZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(5f) }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }

                    val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        override fun onScale(detector: ScaleGestureDetector): Boolean {
                            val cam = activeCamera ?: return false
                            val current = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                            val factor = detector.scaleFactor
                            val maxZ = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 5f
                            val minZ = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1f
                            val target = (current * factor).coerceIn(minZ, min(maxZ, 6f))
                            cam.cameraControl.setZoomRatio(target)
                            return true
                        }
                    })

                    previewView.setOnTouchListener { _, event ->
                        scaleDetector.onTouchEvent(event)
                        true
                    }

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        val reader = MultiFormatReader().apply {
                            setHints(
                                mapOf(
                                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                                    DecodeHintType.TRY_HARDER to true
                                )
                            )
                        }

                        var lastAutoZoomAttempt = 0L

                        imageAnalysis.setAnalyzer(cameraExecutor) { image: ImageProxy ->
                            if (scanned) {
                                image.close()
                                return@setAnalyzer
                            }
                            val buffer = image.planes[0].buffer
                            val data = ByteArray(buffer.remaining())
                            buffer.get(data)
                            val width = image.width
                            val height = image.height
                            val source = PlanarYUVLuminanceSource(
                                data, width, height, 0, 0, width, height, false
                            )
                            val bitmap = BinaryBitmap(HybridBinarizer(source))
                            try {
                                val result = reader.decodeWithState(bitmap)
                                if (!scanned && result.text != null && result.text.isNotBlank()) {
                                    scanned = true
                                    val text = result.text
                                    previewView.post {
                                        onQrCodeScanned(text)
                                    }
                                }
                            } catch (_: NotFoundException) {
                                val now = System.currentTimeMillis()
                                if (now - lastAutoZoomAttempt > 800) {
                                    try {
                                        val detector = Detector(bitmap.blackMatrix)
                                        val detectResult = detector.detect()
                                        val points = detectResult.points
                                        if (points != null && points.size >= 3) {
                                            val p0 = points[0]
                                            val p1 = points[1]
                                            val p2 = points[2]
                                            val d01 = hypot(p0.x - p1.x, p0.y - p1.y)
                                            val d12 = hypot(p1.x - p2.x, p1.y - p2.y)
                                            val qrDimension = max(d01, d12)
                                            val minFrameDimension = min(width, height)
                                            if (qrDimension > 15 && qrDimension < minFrameDimension * 0.30f) {
                                                val suggestedZoom = (minFrameDimension * 0.48f / qrDimension).coerceIn(1.5f, 3.5f)
                                                val cam = activeCamera
                                                if (cam != null) {
                                                    val curZ = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                                                    if (suggestedZoom > curZ + 0.35f) {
                                                        lastAutoZoomAttempt = now
                                                        cam.cameraControl.setZoomRatio(suggestedZoom)
                                                    }
                                                }
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                            } catch (_: Exception) {
                            } finally {
                                image.close()
                            }
                        }

                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                        try {
                            cameraProvider.unbindAll()
                            val cam = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageAnalysis
                            )
                            activeCamera = cam
                            cam.cameraInfo.zoomState.observe(lifecycleOwner) { zState ->
                                currentZoom = zState.zoomRatio
                                maxZoom = zState.maxZoomRatio
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }, androidx.core.content.ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            Box(
                modifier = Modifier
                    .size(260.dp)
                    .align(Alignment.Center)
                    .border(
                        BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
                        RoundedCornerShape(20.dp)
                    )
            )

            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = 160.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                listOf(1f, 2f, 3f).forEach { zoomLevel ->
                    val isSelected = (currentZoom - zoomLevel).let { it >= -0.3f && it <= 0.3f }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.55f)
                            )
                            .clickable {
                                activeCamera?.cameraControl?.setZoomRatio(zoomLevel)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${zoomLevel.toInt()}x",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 40.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Сканирование QR-кода",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Закрыть",
                        tint = Color.White
                    )
                }
            }

            Text(
                text = "Наведите камеру на QR-код • Щипок или кнопки для зума",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 60.dp)
            )
        }
    }
}
