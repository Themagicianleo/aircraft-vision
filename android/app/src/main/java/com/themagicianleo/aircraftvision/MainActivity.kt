package com.themagicianleo.aircraftvision

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light status-bar icons: the camera and photo behind them are dark.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            AircraftTheme { AircraftApp(viewModel) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(viewModel::onImage)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AircraftApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showTypes by rememberSaveable { mutableStateOf(false) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::onImage)
    }
    val openGallery = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    val showingResult = state.image != null || state.busy || state.predictions.isNotEmpty()
    if (showingResult) {
        ResultScreen(state, onClose = viewModel::clear, onCrop = viewModel::onCrop)
    } else {
        CameraScreen(
            error = state.error,
            onCapture = viewModel::onBitmap,
            onGallery = openGallery,
            onShowTypes = { showTypes = true },
        )
    }

    if (showTypes) {
        ModalBottomSheet(onDismissRequest = { showTypes = false }) {
            SupportedTypes(state.supportedTypes)
        }
    }
}

// ------------------------------------------------------------------ camera

@Composable
private fun CameraScreen(error: String?, onCapture: (Bitmap) -> Unit, onGallery: () -> Unit, onShowTypes: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) requestPermission.launch(Manifest.permission.CAMERA) }

    var torch by remember { mutableStateOf(false) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val controller = remember {
        LifecycleCameraController(context).apply { cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA }
    }
    if (granted) {
        DisposableEffect(lifecycleOwner) {
            controller.bindToLifecycle(lifecycleOwner)
            onDispose { controller.unbind() }
        }
        LaunchedEffect(torch) { controller.enableTorch(torch) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            // Pinch-to-zoom and tap-to-focus come with the camera controller.
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        this.controller = controller
                        previewView = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            Viewfinder(Modifier.fillMaxSize())
        } else {
            NoCameraPermission(
                onAllow = { requestPermission.launch(Manifest.permission.CAMERA) },
                onSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                },
            )
        }

        // Top bar
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(if (torch) R.drawable.ic_flash_on else R.drawable.ic_flash_off, "Torch", enabled = granted) { torch = !torch }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_flight), null, tint = Color.White, modifier = Modifier.size(20.dp).rotate(45f))
                Spacer(Modifier.width(8.dp))
                Text("Spotter", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            GlassIconButton(R.drawable.ic_list, "Aircraft it knows", onClick = onShowTypes)
        }

        // Bottom controls
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            error?.let {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.padding(16.dp)) {
                    Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                if (granted) "Point at an aircraft and tap search" else "Or pick a photo from your gallery",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 36.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(R.drawable.ic_gallery, "Choose from gallery", size = 52.dp, onClick = onGallery)
                ShutterButton(enabled = granted) { previewView?.bitmap?.let(onCapture) }
                Spacer(Modifier.size(52.dp))  // balances the gallery button
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "Aircraft",
                style = MaterialTheme.typography.labelLarge,
                color = Color.Black,
                modifier = Modifier.clip(CircleShape).background(Color.White).padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
}

/** Rounded corner brackets around the centre square: the part of the frame the model analyses. */
@Composable
private fun Viewfinder(modifier: Modifier) {
    Canvas(modifier) {
        val side = min(size.width, size.height) * 0.82f
        drawCornerBrackets(Rect(Offset((size.width - side) / 2, (size.height - side) / 2), Size(side, side)), Color.White)
    }
}

private fun DrawScope.drawCornerBrackets(box: Rect, color: Color) {
    val short = min(box.width, box.height)
    val arm = short * 0.18f
    val r = short * 0.07f  // corner radius
    val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
    // Each bracket: a quarter-circle corner with two short straight arms.
    data class Corner(val cx: Float, val cy: Float, val start: Float, val dx: Float, val dy: Float)
    listOf(
        Corner(box.left + r, box.top + r, 180f, 1f, 1f), Corner(box.right - r, box.top + r, 270f, -1f, 1f),
        Corner(box.right - r, box.bottom - r, 0f, -1f, -1f), Corner(box.left + r, box.bottom - r, 90f, 1f, -1f),
    ).forEach { c ->
        drawArc(color, c.start, 90f, false, Offset(c.cx - r, c.cy - r), Size(2 * r, 2 * r), style = stroke)
        val ex = c.cx - c.dx * r  // x of the vertical edge
        val ey = c.cy - c.dy * r  // y of the horizontal edge
        drawLine(color, Offset(c.cx, ey), Offset(c.cx + c.dx * (arm - r), ey), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(ex, c.cy), Offset(ex, c.cy + c.dy * (arm - r)), stroke.width, StrokeCap.Round)
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(84.dp)
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Color.White.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_search), "Identify", tint = Color(0xFF0B63B6), modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun GlassIconButton(icon: Int, description: String, size: Dp = 44.dp, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), description, tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun NoCameraPermission(onAllow: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(R.drawable.ic_search), null, tint = Color.White, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text("Identify aircraft with your camera", style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Allow camera access to point at a plane and identify it. Photos never leave your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAllow) { Text("Allow camera") }
        TextButton(onClick = onSettings) { Text("Open settings", color = Color.White) }
    }
}

// ------------------------------------------------------------------ result

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultScreen(state: UiState, onClose: () -> Unit, onCrop: (CropBox) -> Unit) {
    BackHandler(onBack = onClose)
    val peek = (LocalConfiguration.current.screenHeightDp * 0.4f).dp
    BottomSheetScaffold(
        scaffoldState = rememberBottomSheetScaffoldState(),
        sheetPeekHeight = peek,
        sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        containerColor = Color.Black,
        sheetContent = { SheetContent(state, onRetake = onClose, onCrop = onCrop) },
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val image = state.image
            val crop = state.crop
            if (image != null && crop != null) {
                CropEditor(image, crop, analysing = state.busy, bottomInset = peek - 16.dp, onCropChanged = onCrop)
            }
            Row(Modifier.statusBarsPadding().padding(12.dp)) {
                GlassIconButton(R.drawable.ic_close, "Back to camera", onClick = onClose)
            }
        }
    }
}

private enum class Handle { None, Move, TopLeft, TopRight, BottomLeft, BottomRight }

/**
 * The whole photo with a box marking what gets identified. Drag a corner to resize the box,
 * drag inside it to move it; letting go identifies the new area. Sparkles show while it works.
 */
@Composable
private fun CropEditor(image: Bitmap, crop: CropBox, analysing: Boolean, bottomInset: Dp, onCropChanged: (CropBox) -> Unit) {
    val sparkle = rememberInfiniteTransition(label = "sparkle")
    val time by sparkle.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "t",
    )
    val dots = remember { List(40) { Random(it).let { r -> Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) } } }
    val accent = MaterialTheme.colorScheme.primary

    var box by remember(image) { mutableStateOf(crop) }
    LaunchedEffect(crop) { box = crop }  // e.g. "Reset box" from the sheet
    var area by remember { mutableStateOf(Rect.Zero) }  // where the photo is drawn, in px
    val latestOnCropChanged by rememberUpdatedState(onCropChanged)

    Canvas(
        Modifier
            .fillMaxSize()
            .padding(bottom = bottomInset)
            .padding(20.dp)  // room to grab corners at the photo's edges
            .onSizeChanged { sz ->
                val scale = min(sz.width.toFloat() / image.width, sz.height.toFloat() / image.height)
                val w = image.width * scale
                val h = image.height * scale
                area = Rect(Offset((sz.width - w) / 2, (sz.height - h) / 2), Size(w, h))
            }
            .pointerInput(image) {
                var handle = Handle.None
                val minSide = 56.dp.toPx()
                detectDragGestures(
                    onDragStart = { p -> handle = hitTest(p, box.toPx(area), 40.dp.toPx()) },
                    onDragEnd = {
                        if (handle != Handle.None) latestOnCropChanged(box)
                        handle = Handle.None
                    },
                    onDragCancel = { handle = Handle.None },
                ) { change, drag ->
                    if (handle != Handle.None && area.width > 0f) {
                        change.consume()
                        box = box.dragged(handle, drag.x / area.width, drag.y / area.height, minSide / area.width, minSide / area.height)
                    }
                }
            },
    ) {
        drawImage(
            image.asImageBitmap(),
            dstOffset = IntOffset(area.left.toInt(), area.top.toInt()),
            dstSize = IntSize(area.width.toInt(), area.height.toInt()),
        )
        val b = box.toPx(area)
        // Dim everything outside the box.
        val dim = Color.Black.copy(alpha = 0.5f)
        drawRect(dim, area.topLeft, Size(area.width, b.top - area.top))
        drawRect(dim, Offset(area.left, b.bottom), Size(area.width, area.bottom - b.bottom))
        drawRect(dim, Offset(area.left, b.top), Size(b.left - area.left, b.height))
        drawRect(dim, Offset(b.right, b.top), Size(area.right - b.right, b.height))
        drawRect(Color.White.copy(alpha = 0.5f), b.topLeft, b.size, style = Stroke(1.dp.toPx()))
        if (analysing) {
            dots.forEach { (x, y, phase) ->
                val a = ((sin((time + phase) * 2 * PI) + 1) / 2).toFloat().let { it * it }
                val c = Offset(b.left + x * b.width, b.top + y * b.height)
                drawCircle(accent.copy(alpha = a * 0.35f), radius = (6 + 8 * a).dp.toPx() / 2, center = c)
                drawCircle(Color.White.copy(alpha = a), radius = (2 + 3 * a).dp.toPx() / 2, center = c)
            }
        }
        drawCornerBrackets(b, Color.White)
    }
}

private fun CropBox.toPx(area: Rect) = Rect(
    area.left + left * area.width, area.top + top * area.height,
    area.left + right * area.width, area.top + bottom * area.height,
)

private fun hitTest(p: Offset, box: Rect, touch: Float): Handle {
    val corners = listOf(
        Handle.TopLeft to box.topLeft, Handle.TopRight to box.topRight,
        Handle.BottomLeft to box.bottomLeft, Handle.BottomRight to box.bottomRight,
    )
    val (handle, corner) = corners.minBy { (_, c) -> (c - p).getDistance() }
    return when {
        (corner - p).getDistance() <= touch -> handle
        box.contains(p) -> Handle.Move
        else -> Handle.None
    }
}

/** The box after dragging [handle] by (dx, dy), all in 0..1 image units; stays inside the photo. */
private fun CropBox.dragged(handle: Handle, dx: Float, dy: Float, minW: Float, minH: Float): CropBox = when (handle) {
    Handle.Move -> {
        val w = right - left
        val h = bottom - top
        val l = (left + dx).coerceIn(0f, 1f - w)
        val t = (top + dy).coerceIn(0f, 1f - h)
        CropBox(l, t, l + w, t + h)
    }
    Handle.TopLeft -> copy(left = (left + dx).coerceIn(0f, right - minW), top = (top + dy).coerceIn(0f, bottom - minH))
    Handle.TopRight -> copy(right = (right + dx).coerceIn(left + minW, 1f), top = (top + dy).coerceIn(0f, bottom - minH))
    Handle.BottomLeft -> copy(left = (left + dx).coerceIn(0f, right - minW), bottom = (bottom + dy).coerceIn(top + minH, 1f))
    Handle.BottomRight -> copy(right = (right + dx).coerceIn(left + minW, 1f), bottom = (bottom + dy).coerceIn(top + minH, 1f))
    Handle.None -> this
}

@Composable
private fun SheetContent(state: UiState, onRetake: () -> Unit, onCrop: (CropBox) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val image = state.image
        if (image != null && !state.busy) {
            val centre = CropBox.centreSquare(image.width, image.height)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Drag the box corners to fit the aircraft",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (state.crop != centre) TextButton(onClick = { onCrop(centre) }) { Text("Reset box") }
            }
        }
        when {
            state.error != null -> ErrorCard(state.error)
            state.busy || state.predictions.isEmpty() -> Searching()
            else -> ResultDetails(state.predictions, state.inferenceMs)
        }
        if (!state.busy) {
            OutlinedButton(onClick = onRetake, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(painterResource(R.drawable.ic_search), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Search another aircraft")
            }
        }
    }
}

@Composable
private fun Searching() {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
        Spacer(Modifier.width(12.dp))
        Text("Looking closely…", style = MaterialTheme.typography.titleMedium)
    }
    // Skeleton of the result card while the model runs.
    val skeleton = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
    Box(Modifier.fillMaxWidth(0.6f).height(34.dp).clip(RoundedCornerShape(10.dp)).background(skeleton))
    Box(Modifier.fillMaxWidth(0.4f).height(18.dp).clip(RoundedCornerShape(8.dp)).background(skeleton))
    repeat(3) { Box(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(skeleton)) }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_info), null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SupportedTypes(types: List<AircraftLabel>) {
    var query by remember { mutableStateOf("") }
    val groups = remember(types, query) {
        types
            .filter { query.isBlank() || listOf(it.name, it.family, it.manufacturer).any { s -> s.contains(query, ignoreCase = true) } }
            .groupBy { it.manufacturer.ifBlank { "Other" } }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text("Aircraft it knows", style = MaterialTheme.typography.headlineMedium)
        Text(
            "${types.size} types from ${types.map { it.manufacturer }.distinct().size} manufacturers",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search, e.g. 787 or Airbus") },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), modifier = Modifier.fillMaxWidth()) {
            groups.forEach { (manufacturer, list) ->
                item(key = "h_$manufacturer") {
                    Text(
                        manufacturer.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                    )
                }
                items(list.sortedBy { it.name }, key = { "t_${it.name}" }) { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (t.family.isNotBlank() && t.family != t.name) {
                            Text(t.family, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

