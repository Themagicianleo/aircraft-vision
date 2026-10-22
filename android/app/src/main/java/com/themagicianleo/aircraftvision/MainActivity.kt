package com.themagicianleo.aircraftvision

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            AircraftTheme { AircraftScreen(viewModel) }
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
private fun AircraftScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingCapture by rememberSaveable { mutableStateOf<Uri?>(null) }
    var showTypes by rememberSaveable { mutableStateOf(false) }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::onImage)
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (saved) pendingCapture?.let(viewModel::onImage)
    }
    val openGallery = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val openCamera = {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        pendingCapture = uri
        takePhoto.launch(uri)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        Header(typeCount = state.supportedTypes.size, onShowTypes = { showTypes = true })

        Column(
            Modifier
                .offset(y = (-28).dp)
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            PhotoCard(image = state.image, scanning = state.busy, onClick = openGallery)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = openCamera,
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_camera), contentDescription = null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Camera", style = MaterialTheme.typography.labelLarge)
                }
                FilledTonalButton(
                    onClick = openGallery,
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_gallery), contentDescription = null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Gallery", style = MaterialTheme.typography.labelLarge)
                }
            }

            state.error?.let { ErrorCard(it) }

            AnimatedVisibility(
                visible = state.predictions.isNotEmpty(),
                enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 6 },
            ) {
                Results(state.predictions, state.inferenceMs)
            }

            if (state.predictions.isEmpty() && !state.busy) Tips()
        }
    }

    if (showTypes) {
        ModalBottomSheet(onDismissRequest = { showTypes = false }) {
            SupportedTypes(state.supportedTypes)
        }
    }
}

// ------------------------------------------------------------------ header

@Composable
private fun Header(typeCount: Int, onShowTypes: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
            .background(SkyGradient),
    ) {
        // A big faint aircraft silhouette climbing out of the corner.
        Icon(
            painterResource(R.drawable.ic_flight),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.07f),
            modifier = Modifier
                .size(220.dp)
                .align(Alignment.TopEnd)
                .offset(x = 50.dp, y = 10.dp)
                .rotate(45f),
        )
        Column(
            Modifier
                .statusBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 52.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_flight), null, tint = Color.White, modifier = Modifier.size(20.dp).rotate(45f))
                }
                Spacer(Modifier.width(10.dp))
                Text("AIRCRAFT VISION", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f))
            }
            Spacer(Modifier.height(18.dp))
            Text("What's that plane?", style = MaterialTheme.typography.displaySmall, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text(
                "Snap or choose a photo and it's identified right on your phone.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeaderChip(R.drawable.ic_offline, "Works offline")
                if (typeCount > 0) HeaderChip(R.drawable.ic_list, "$typeCount aircraft types", onClick = onShowTypes)
            }
        }
    }
}

@Composable
private fun HeaderChip(icon: Int, text: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Color.White)
        if (onClick != null) Text("  ›", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
    }
}

// -------------------------------------------------------------- photo card

@Composable
private fun PhotoCard(image: Bitmap?, scanning: Boolean, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(28.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (image == null) {
            EmptyPhoto(onClick)
        } else {
            Box {
                Image(
                    image.asImageBitmap(),
                    contentDescription = "Selected photo",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth(),
                )
                // The model looks at the centre square of the photo: show it, and scan it while working.
                FocusSquare(
                    aspect = image.width.toFloat() / image.height,
                    scanning = scanning,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

@Composable
private fun EmptyPhoto(onClick: () -> Unit) {
    val dash = MaterialTheme.colorScheme.outlineVariant
    Column(
        Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clickable(onClick = onClick)
            .padding(14.dp)
            .drawBehind {
                drawRoundRect(
                    color = dash,
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f))),
                    cornerRadius = CornerRadius(20.dp.toPx()),
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_flight), null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(36.dp).rotate(45f),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text("No photo yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Tap here or use the buttons below",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FocusSquare(aspect: Float, scanning: Boolean, modifier: Modifier) {
    val sweep = rememberInfiniteTransition(label = "scan")
    val progress by sweep.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "scanLine",
    )
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val side = min(size.width, size.height)
        val left = (size.width - side) / 2
        val top = (size.height - side) / 2
        val dim = Color.Black.copy(alpha = 0.45f)
        // Dim what the model ignores (only visible for non-square photos).
        if (aspect > 1f) {
            drawRect(dim, Offset.Zero, Size(left, size.height))
            drawRect(dim, Offset(left + side, 0f), Size(left, size.height))
        } else if (aspect < 1f) {
            drawRect(dim, Offset.Zero, Size(size.width, top))
            drawRect(dim, Offset(0f, top + side), Size(size.width, top))
        }
        // Viewfinder corner brackets.
        val arm = side * 0.12f
        val inset = side * 0.04f
        val stroke = 3.dp.toPx()
        val l = left + inset
        val t = top + inset
        val r = left + side - inset
        val b = top + side - inset
        listOf(
            Offset(l, t) to Offset(1f, 1f), Offset(r, t) to Offset(-1f, 1f),
            Offset(l, b) to Offset(1f, -1f), Offset(r, b) to Offset(-1f, -1f),
        ).forEach { (c, d) ->
            drawLine(Color.White, c, Offset(c.x + arm * d.x, c.y), stroke, StrokeCap.Round)
            drawLine(Color.White, c, Offset(c.x, c.y + arm * d.y), stroke, StrokeCap.Round)
        }
        if (scanning) {
            val y = t + (b - t) * progress
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.35f)), startY = y - 60f, endY = y),
                Offset(l, y - 60f), Size(r - l, 60f),
            )
            drawLine(accent, Offset(l, y), Offset(r, y), 2.5.dp.toPx(), StrokeCap.Round)
        }
    }
}

// ----------------------------------------------------------------- results

@Composable
private fun Results(predictions: List<Prediction>, inferenceMs: Long?) {
    val best = predictions.firstOrNull() ?: return  // empty while the card animates away
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(Modifier.padding(22.dp)) {
                Text("IDENTIFIED AS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            best.label.name,
                            style = MaterialTheme.typography.displaySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (best.label.manufacturer.isNotBlank()) Pill(best.label.manufacturer, strong = true)
                            if (best.label.family.isNotBlank() && best.label.family != best.label.name) Pill(best.label.family)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    ConfidenceRing(best.confidence)
                }
                if (best.confidence < LOW_CONFIDENCE) {
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_info), null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Not sure about this one. A sharper photo with the aircraft filling the square works best.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
        }

        if (predictions.size > 1) {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("COULD ALSO BE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    predictions.drop(1).forEachIndexed { i, p -> AlternativeRow(rank = i + 2, prediction = p) }
                }
            }
        }

        inferenceMs?.let {
            Text(
                "Analysed on your phone in $it ms · no internet used",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Pill(text: String, strong: Boolean = false) {
    Surface(
        shape = CircleShape,
        color = if (strong) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (strong) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ConfidenceRing(confidence: Float, size: Dp = 92.dp) {
    val animated = remember { Animatable(0f) }
    LaunchedEffect(confidence) { animated.animateTo(confidence, tween(900)) }
    val color = confidenceColor(confidence)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round)
            drawArc(track, -90f, 360f, useCenter = false, style = stroke)
            drawArc(color, -90f, 360f * animated.value, useCenter = false, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(animated.value * 100).toInt()}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("sure", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AlternativeRow(rank: Int, prediction: Prediction) {
    val animated = remember { Animatable(0f) }
    LaunchedEffect(prediction) { animated.animateTo(prediction.confidence, tween(700, delayMillis = 100 * rank)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("$rank", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(prediction.label.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    "${(prediction.confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                listOf(prediction.label.manufacturer, prediction.label.family)
                    .filter { it.isNotBlank() && it != prediction.label.name }.distinct().joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(animated.value.coerceIn(0.02f, 1f))
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

// ------------------------------------------------------------ misc cards

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
private fun Tips() {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("TIPS FOR A GOOD MATCH", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Tip("1", "Let the aircraft fill most of the frame")
            Tip("2", "Side or three-quarter views work best")
            Tip("3", "Zoom in rather than shooting a dot in the sky")
        }
    }
}

@Composable
private fun Tip(number: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(number, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
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

private const val LOW_CONFIDENCE = 0.4f
