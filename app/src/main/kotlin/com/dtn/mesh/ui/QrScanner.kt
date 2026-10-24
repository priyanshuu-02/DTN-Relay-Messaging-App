package com.dtn.mesh.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dtn.mesh.ui.theme.DtnColors
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Fraction of the preview width trimmed from each side to form the square decode window
 * (BarcodeView's framing rect). The overlay cut-out uses the same value so what the user aims at
 * is exactly what gets decoded.
 */
private const val SCAN_MARGIN_FRACTION = 0.14f

/** Light blue of the frame corners and the sweeping line (stands out on the dimmed camera image). */
private val SCAN_ACCENT = Color(0xFF12A0D8)

/** Scan + upload entry points, plus a busy flag while an uploaded image is being decoded. */
class QrActions internal constructor(
    val scan: () -> Unit,
    val upload: () -> Unit,
    val decoding: Boolean,
)

/**
 * Hosts the QR scanner (shown while scanning) and the photo picker. [onResult] receives the raw
 * text of the code from either path; [onProblem] gets a user-facing explanation when an uploaded
 * image has no readable QR code.
 */
@Composable
fun rememberQrActions(
    prompt: String,
    onResult: (String) -> Unit,
    onProblem: (String) -> Unit,
): QrActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by rememberSaveable { mutableStateOf(false) }
    var decoding by remember { mutableStateOf(false) }
    val latestResult by rememberUpdatedState(onResult)
    val latestProblem by rememberUpdatedState(onProblem)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                decoding = true
                val text = QrImageDecoder.decode(context, uri)
                decoding = false
                if (text != null) latestResult(text)
                else latestProblem("No QR code found in that image. Try a sharper, closer screenshot.")
            }
        }
    }
    val upload = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    if (scanning) {
        QrScannerDialog(
            prompt = prompt,
            onResult = { scanning = false; latestResult(it) },
            onUpload = { scanning = false; upload() },
            onDismiss = { scanning = false },
        )
    }
    return QrActions(scan = { scanning = true }, upload = upload, decoding = decoding)
}

/**
 * Full-screen portrait QR scanner: live camera preview with a viewfinder, torch toggle, and an
 * "upload image" escape hatch. Replaces zxing's stock CaptureActivity, which the library manifest
 * hard-locks to landscape.
 */
@Composable
fun QrScannerDialog(
    prompt: String,
    onResult: (String) -> Unit,
    onUpload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    // Hold portrait while the scanner is open, then restore whatever the app had.
    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            activity?.requestedOrientation = previous ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCamera = granted
        askedOnce = true
    }
    LaunchedEffect(Unit) { if (!hasCamera && !askedOnce) cameraPermission.launch(Manifest.permission.CAMERA) }

    var torchOn by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var delivered by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Sized to the window, not "fill max": see [rememberBetweenBarsHeight].
        val height = rememberBetweenBarsHeight()
        Box(
            modifier = (if (height != null) Modifier.fillMaxWidth().height(height) else Modifier.fillMaxSize())
                .background(Color.Black),
        ) {
            if (hasCamera && cameraError == null) {
                CameraScanView(
                    torchOn = torchOn,
                    onCode = { code ->
                        if (!delivered) {
                            delivered = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onResult(code)
                        }
                    },
                    onError = { cameraError = it },
                )
                ScanOverlay()
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        cameraError ?: "Camera access is off",
                        color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (cameraError != null) "You can still upload a screenshot or photo of the QR code."
                        else "Allow the camera to scan, or upload a screenshot / photo of the QR code instead.",
                        color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    if (cameraError == null) {
                        Button(onClick = { cameraPermission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                        TextButton(onClick = { openAppSettings(context) }) {
                            Text("Open app settings", color = Color.White)
                        }
                    }
                }
            }

            // Top: prompt + close (below the status bar when the dialog draws edge to edge).
            Row(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    prompt,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onDismiss) { Text("Close", color = Color.White, fontSize = 15.sp) }
            }

            // Bottom: hint, torch, upload — lifted well clear of the bottom edge (gesture bar,
            // thumb reach) and of the navigation bar when the dialog draws edge to edge.
            Column(
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (hasCamera && cameraError == null) {
                    Text(
                        "Hold the code inside the frame — it scans automatically",
                        color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (hasCamera && cameraError == null) {
                        FilledTonalButton(
                            onClick = { torchOn = !torchOn },
                            modifier = Modifier.heightIn(min = 52.dp)
                                .semantics { contentDescription = if (torchOn) "Turn torch off" else "Turn torch on" },
                        ) { Text(if (torchOn) "🔦 Torch on" else "🔦 Torch", fontSize = 15.sp) }
                    }
                    Button(
                        onClick = onUpload,
                        modifier = Modifier.heightIn(min = 52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DtnColors.Primary),
                    ) { Text("🖼 Upload image", fontSize = 15.sp) }
                }
            }
        }
    }
}

/**
 * Height of the space between the status and navigation bars (API 30+), or null on older phones.
 *
 * From Android 15, `Configuration.screenHeightDp` includes the system bars, and Compose sizes a
 * full-width dialog's content from it — while the dialog window itself still sits between the
 * bars. A `fillMaxSize()` scanner was therefore laid out ~90 dp taller than its window, and the
 * torch / upload buttons ended up at the very bottom edge, partly behind the navigation bar.
 * Sizing the content to the real window height keeps the bottom padding where it's meant to be.
 */
@Composable
private fun rememberBetweenBarsHeight(): Dp? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    return remember(context, density, configuration) {
        val metrics = context.getSystemService(WindowManager::class.java)?.currentWindowMetrics ?: return@remember null
        val bars = metrics.windowInsets.getInsets(android.view.WindowInsets.Type.systemBars())
        val px = metrics.bounds.height() - bars.top - bars.bottom
        if (px > 0) with(density) { px.toDp() } else null
    }
}

/** zxing [BarcodeView] (TextureView preview, QR-only decoder) bound to the lifecycle. */
@Composable
private fun CameraScanView(torchOn: Boolean, onCode: (String) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnCode by rememberUpdatedState(onCode)
    val latestOnError by rememberUpdatedState(onError)
    val view = remember {
        BarcodeView(context).apply {
            setUseTextureView(true)
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            setMarginFraction(SCAN_MARGIN_FRACTION.toDouble())
        }
    }
    DisposableEffect(view, lifecycleOwner) {
        view.addStateListener(object : CameraPreview.StateListener {
            override fun previewSized() = Unit
            override fun previewStarted() = Unit
            override fun previewStopped() = Unit
            override fun cameraClosed() = Unit
            override fun cameraError(error: Exception) {
                latestOnError("Camera unavailable${error.message?.let { ": $it" } ?: ""}")
            }
        })
        view.decodeContinuous { result -> result.text?.let { latestOnCode(it) } }
        // addObserver replays the current state, so an already-resumed screen starts at once.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.resume()
                Lifecycle.Event.ON_PAUSE -> view.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { view.setTorch(false) }
            view.pause()
        }
    }
    LaunchedEffect(torchOn) { runCatching { view.setTorch(torchOn) } }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}

/** Dimmed surround with a clear square window, corner brackets and a sweeping scan line. */
@Composable
private fun ScanOverlay() {
    val transition = rememberInfiniteTransition(label = "scan")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse),
        label = "sweep",
    )
    val accent = SCAN_ACCENT
    Canvas(
        modifier = Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
    ) {
        val side = size.width * (1f - 2f * SCAN_MARGIN_FRACTION)
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val radius = 18.dp.toPx()
        drawRect(Color.Black.copy(alpha = 0.58f))
        drawRoundRect(
            color = Color.Transparent,
            topLeft = Offset(left, top),
            size = Size(side, side),
            cornerRadius = CornerRadius(radius),
            blendMode = BlendMode.Clear,
        )
        val stroke = 4.dp.toPx()
        val len = side * 0.14f
        val right = left + side
        val bottom = top + side
        listOf(
            Offset(left, top) to Pair(Offset(len, 0f), Offset(0f, len)),
            Offset(right, top) to Pair(Offset(-len, 0f), Offset(0f, len)),
            Offset(left, bottom) to Pair(Offset(len, 0f), Offset(0f, -len)),
            Offset(right, bottom) to Pair(Offset(-len, 0f), Offset(0f, -len)),
        ).forEach { (corner, arms) ->
            drawLine(accent, corner, corner + arms.first, stroke, StrokeCap.Round)
            drawLine(accent, corner, corner + arms.second, stroke, StrokeCap.Round)
        }
        val y = top + side * (0.06f + 0.88f * sweep)
        drawLine(
            accent.copy(alpha = 0.9f),
            Offset(left + 14.dp.toPx(), y),
            Offset(right - 14.dp.toPx(), y),
            2.dp.toPx(),
            StrokeCap.Round,
        )
    }
}

/**
 * QR code rendered off the main thread at one pixel per module, then scaled up with
 * nearest-neighbour filtering (crisp edges, near-zero cost).
 */
@Composable
fun QrImage(content: String, modifier: Modifier = Modifier, size: Dp = 220.dp) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, content) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val m = QrCodec.encode(content)
                Bitmap.createBitmap(QrCodec.toPixels(m), m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        modifier = modifier.size(size).background(Color.White, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(
                bitmap = b,
                contentDescription = "QR code",
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        }
    }
}

/** Decode a QR code from a picked image (gallery / files). */
object QrImageDecoder {
    private const val MAX_DIM = 1600
    private const val RETRY_DIM = 800

    suspend fun decode(context: Context, uri: Uri): String? = withContext(Dispatchers.Default) {
        val bmp = runCatching { loadBitmap(context, uri, MAX_DIM) }.getOrNull() ?: return@withContext null
        decodeBitmap(bmp) ?: run {
            // Very large or noisy photos sometimes decode better smaller.
            val largest = max(bmp.width, bmp.height)
            if (largest <= RETRY_DIM) return@run null
            val s = RETRY_DIM.toFloat() / largest
            val small = Bitmap.createScaledBitmap(
                bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true,
            )
            decodeBitmap(small)
        }
    }

    private fun decodeBitmap(b: Bitmap): String? {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        // Flatten transparency onto white: a transparent-background QR would otherwise read as
        // black-on-black (transparent pixels are usually stored as 0x00000000).
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a == 255) continue
            fun ch(shift: Int) = (((c shr shift) and 0xFF) * a + 255 * (255 - a)) / 255
            px[i] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        return QrCodec.decode(px, b.width, b.height)
    }

    private fun loadBitmap(context: Context, uri: Uri, maxDim: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // getPixels needs a software bitmap
                val w = info.size.width
                val h = info.size.height
                val largest = max(w, h)
                if (largest > maxDim) {
                    val s = maxDim.toFloat() / largest
                    decoder.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
}

/** Share a QR code as a PNG (e.g. over WhatsApp) so the other phone can upload it. */
object QrShare {
    fun share(context: Context, content: String, chooserTitle: String): Boolean = runCatching {
        startChooser(context, writePng(context, content), chooserTitle)
    }.isSuccess

    /** [share] with the PNG encoded and written on the IO dispatcher (the new pages use this). */
    suspend fun shareAsync(context: Context, content: String, chooserTitle: String): Boolean = runCatching {
        val file = withContext(Dispatchers.IO) { writePng(context, content) }
        startChooser(context, file, chooserTitle)
    }.isSuccess

    private fun writePng(context: Context, content: String): File {
        val m = QrCodec.encode(content, marginModules = 4)
        val scale = (1024 / m.width).coerceAtLeast(4)
        val bmp = Bitmap.createBitmap(QrCodec.toPixels(m, scale), m.width * scale, m.height * scale, Bitmap.Config.ARGB_8888)
        val dir = File(context.cacheDir, "qr").apply { mkdirs() }
        val file = File(dir, "dtn-qr.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private fun startChooser(context: Context, file: File, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("QR code", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, chooserTitle)
        if (context.findActivity() == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}

internal fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

internal fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .apply { if (context.findActivity() == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
        )
    }
}
