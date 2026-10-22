package com.themagicianleo.aircraftvision

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.min

/**
 * The identification, laid out like a cabin spec sheet: type name, a top-down drawing of a
 * typical layout for that type, a spec table, then the runners-up.
 */
@Composable
fun ResultDetails(predictions: List<Prediction>, inferenceMs: Long?) {
    val best = predictions.first()
    val specs = remember(best.label.name) { specsFor(best.label.name) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // Close variants (737-800 vs -900) are the hardest call. When the model is torn between
    // variants but sure of the family, say the family rather than guess a variant.
    val familyMode = best.familySize > 1 && best.confidence < 0.6f && best.familyConfidence >= 0.75f
    val score = if (familyMode) best.familyConfidence else best.confidence
    val likelyVariants = predictions.filter { it.label.family == best.label.family }.take(2)

    Column {
        // Title block
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(best.label.manufacturer.uppercase(), style = MaterialTheme.typography.labelSmall, color = muted)
                Text(
                    if (familyMode) familyTitle(best.label) else best.label.name,
                    fontSize = 36.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-1).sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (familyMode) {
                    Text(
                        "Variant unclear · most likely " + likelyVariants.joinToString(" or ") { it.label.name },
                        style = MaterialTheme.typography.bodyMedium,
                        color = muted,
                    )
                } else if (best.label.family.isNotBlank() && best.label.family != best.label.name) {
                    Text(best.label.family, style = MaterialTheme.typography.bodyMedium, color = muted)
                }
            }
            MatchScore(score)
        }

        if (score < LOW_CONFIDENCE) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_info), null, tint = ConfidenceMid, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Low match. Fit the box tightly around one aircraft.",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
        }

        // Top-down layout drawing
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(vertical = 14.dp, horizontal = 10.dp),
        ) {
            Planform(specs, Modifier.fillMaxWidth().height(116.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (specs.seatGroups.isNotEmpty() && specs.kind !in COCKPIT_ONLY) {
                LegendSwatch(MaterialTheme.colorScheme.secondary, "Premium")
                Spacer(Modifier.width(14.dp))
                LegendSwatch(MaterialTheme.colorScheme.primary, "Economy")
            }
            Spacer(Modifier.weight(1f))
            Text("Typical layout · illustrative", style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic, letterSpacing = 0.sp), color = muted)
        }

        // Spec table
        Spacer(Modifier.height(20.dp))
        SectionLabel("Specifications")
        SpecRow("Manufacturer", best.label.manufacturer.ifBlank { "—" })
        SpecRow("Family", best.label.family.ifBlank { best.label.name })
        if (familyMode) {
            SpecRow("Variant", likelyVariants.joinToString(" / ") { "${it.label.name} ${(it.confidence * 100).toInt()}%" })
        }
        SpecRow("Body", specs.kind.description)
        SpecRow("Seating", specs.seating)
        SpecRow("Engines", specs.enginesText, last = true)

        // Runners-up
        if (predictions.size > 1) {
            Spacer(Modifier.height(24.dp))
            SectionLabel("Could also be")
            predictions.drop(1).forEachIndexed { i, p -> RunnerUp(p, last = i == predictions.size - 2) }
        }

        inferenceMs?.let {
            Spacer(Modifier.height(16.dp))
            Text(
                "Identified on your phone in $it ms · no internet used",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** "Boeing 737" -> "Boeing 737 family"; "A320" -> "Airbus A320 family". */
private fun familyTitle(label: AircraftLabel): String {
    val family = label.family.ifBlank { label.name }
    val withMaker = if (label.manufacturer.isNotBlank() && !family.startsWith(label.manufacturer)) "${label.manufacturer} $family" else family
    return "$withMaker family"
}

private val COCKPIT_ONLY = setOf(BodyKind.FIGHTER, BodyKind.LIGHT, BodyKind.TRANSPORT)
private const val LOW_CONFIDENCE = 0.4f

@Composable
private fun MatchScore(confidence: Float) {
    val animated = remember { Animatable(0f) }
    LaunchedEffect(confidence) { animated.animateTo(confidence, tween(800)) }
    Column(horizontalAlignment = Alignment.End) {
        Text(
            "${(animated.value * 100).toInt()}%",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = confidenceColor(confidence),
        )
        Text("MATCH", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun SpecRow(label: String, value: String, last: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(118.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
    if (!last) HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun LegendSwatch(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RunnerUp(prediction: Prediction, last: Boolean) {
    val animated = remember { Animatable(0f) }
    LaunchedEffect(prediction) { animated.animateTo(prediction.confidence, tween(700)) }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(prediction.label.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    listOf(prediction.label.manufacturer, specsFor(prediction.label.name).kind.description)
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${(prediction.confidence * 100).toInt()}%",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(
                Modifier.fillMaxWidth(animated.value.coerceIn(0.01f, 1f)).height(3.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)),
            )
        }
    }
    if (!last) HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

// ------------------------------------------------------------------ drawing

/** Proportions per body kind: fuselage width (of height), length (of width), swept wings. */
private data class Shape(val bodyWidth: Float, val bodyLength: Float, val swept: Boolean)

private fun shapeOf(kind: BodyKind) = when (kind) {
    BodyKind.WIDE -> Shape(0.42f, 0.98f, true)
    BodyKind.NARROW -> Shape(0.32f, 0.96f, true)
    BodyKind.REGIONAL -> Shape(0.28f, 0.86f, true)
    BodyKind.TURBOPROP -> Shape(0.26f, 0.80f, false)
    BodyKind.BUSINESS -> Shape(0.24f, 0.74f, true)
    BodyKind.CLASSIC_PROP -> Shape(0.28f, 0.80f, false)
    BodyKind.TRANSPORT -> Shape(0.34f, 0.86f, false)
    BodyKind.FIGHTER -> Shape(0.20f, 0.70f, true)
    BodyKind.LIGHT -> Shape(0.20f, 0.52f, false)
}

/**
 * A top-down drawing in the style of a cabin layout plan, nose to the left: wings, engines and
 * fuselage outline, with seat rows filled in (premium rows first) for passenger types.
 */
@Composable
fun Planform(specs: AircraftSpecs, modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val premium = MaterialTheme.colorScheme.secondary
    val economy = MaterialTheme.colorScheme.primary
    val skin = MaterialTheme.colorScheme.surface
    Canvas(modifier) {
        val shape = shapeOf(specs.kind)
        val len = size.width * shape.bodyLength
        val bw = size.height * shape.bodyWidth
        val x0 = (size.width - len) / 2
        val cy = size.height / 2
        val noseLen = bw * if (specs.kind == BodyKind.FIGHTER) 2.2f else 1.0f
        val tailLen = bw * 1.7f
        val outline = Stroke(1.5.dp.toPx())

        // Wings, from about 40% of the length, reaching the edges of the drawing.
        val wingRoot = x0 + len * if (specs.kind == BodyKind.FIGHTER) 0.48f else 0.40f
        val rootChord = len * if (specs.kind == BodyKind.FIGHTER) 0.40f else 0.20f
        val tipChord = len * if (shape.swept) 0.07f else 0.11f
        val sweep = if (shape.swept) len * if (specs.kind == BodyKind.FIGHTER) 0.30f else 0.17f else len * 0.02f
        val span = size.height / 2
        for (side in listOf(-1f, 1f)) {
            val wing = Path().apply {
                moveTo(wingRoot, cy + side * bw / 2)
                lineTo(wingRoot + sweep, cy + side * span)
                lineTo(wingRoot + sweep + tipChord, cy + side * span)
                lineTo(wingRoot + rootChord, cy + side * bw / 2)
                close()
            }
            drawPath(wing, ink.copy(alpha = 0.06f))
            drawPath(wing, ink.copy(alpha = 0.3f), style = outline)
            // Tailplane
            val tx = x0 + len - tailLen * 0.75f
            val tail = Path().apply {
                moveTo(tx, cy + side * bw * 0.3f)
                lineTo(tx + tailLen * 0.35f, cy + side * bw * 1.25f)
                lineTo(tx + tailLen * 0.55f, cy + side * bw * 1.25f)
                lineTo(tx + tailLen * 0.6f, cy + side * bw * 0.3f)
                close()
            }
            drawPath(tail, ink.copy(alpha = 0.06f))
            drawPath(tail, ink.copy(alpha = 0.3f), style = outline)
        }

        drawEngines(specs, x0, len, cy, bw, wingRoot, sweep, span, tailLen, ink)

        // Fuselage: rounded nose, straight cabin, tapering tail cone.
        val body = Path().apply {
            moveTo(x0 + noseLen, cy - bw / 2)
            lineTo(x0 + len - tailLen, cy - bw / 2)
            lineTo(x0 + len, cy - bw * 0.08f)
            lineTo(x0 + len, cy + bw * 0.08f)
            lineTo(x0 + len - tailLen, cy + bw / 2)
            lineTo(x0 + noseLen, cy + bw / 2)
            cubicTo(x0 + noseLen * 0.35f, cy + bw / 2, x0, cy + bw * 0.2f, x0, cy)
            cubicTo(x0, cy - bw * 0.2f, x0 + noseLen * 0.35f, cy - bw / 2, x0 + noseLen, cy - bw / 2)
            close()
        }
        drawPath(body, skin)
        drawPath(body, ink.copy(alpha = 0.55f), style = outline)

        val cabinStart = x0 + noseLen + bw * 0.25f
        val cabinEnd = x0 + len - tailLen
        when (specs.kind) {
            BodyKind.FIGHTER, BodyKind.LIGHT -> drawCockpit(specs.seatGroups, x0 + noseLen * 0.9f, cy, bw, ink, economy)
            BodyKind.TRANSPORT -> drawRoundRect(
                ink.copy(alpha = 0.35f), Offset(cabinStart, cy - bw * 0.32f), Size(cabinEnd - cabinStart, bw * 0.64f),
                CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
            )
            else -> drawSeats(specs.seatGroups, cabinStart, cabinEnd, cy, bw, premium, economy)
        }
    }
}

/** Seat rows across the cabin: groups of seats separated by aisles, premium rows at the front. */
private fun DrawScope.drawSeats(groups: List<Int>, start: Float, end: Float, cy: Float, bw: Float, premium: Color, economy: Color) {
    if (groups.isEmpty()) return
    val interior = bw * 0.78f
    val seats = groups.sum()
    val aisles = groups.size - 1
    val unit = min(interior / (seats + aisles * 0.9f), 9.dp.toPx())
    val seat = unit * 0.82f
    val rowPitch = unit * 1.3f
    val rows = floor((end - start) / rowPitch).toInt()
    val premiumRows = maxOf(2, rows / 7)
    val totalWidth = unit * (seats + aisles * 0.9f)
    for (row in 0 until rows) {
        val x = start + row * rowPitch
        var y = cy - totalWidth / 2
        val color = if (row < premiumRows) premium else economy
        groups.forEachIndexed { g, n ->
            repeat(n) {
                drawRoundRect(color.copy(alpha = 0.85f), Offset(x, y + (unit - seat) / 2), Size(seat, seat), CornerRadius(seat * 0.25f))
                y += unit
            }
            if (g < groups.size - 1) y += unit * 0.9f
        }
    }
}

/** Canopy and seats for small and military types; [rows] lists seats per row, front to back. */
private fun DrawScope.drawCockpit(rows: List<Int>, x: Float, cy: Float, bw: Float, ink: Color, seatColor: Color) {
    val seat = bw * 0.26f
    val canopyLen = seat * (rows.size * 1.5f + 0.8f)
    drawOval(ink.copy(alpha = 0.12f), Offset(x, cy - bw * 0.3f), Size(canopyLen, bw * 0.6f))
    rows.forEachIndexed { r, n ->
        val sx = x + seat * 0.6f + r * seat * 1.5f
        repeat(n) { i ->
            val sy = cy - n * seat * 0.6f + i * seat * 1.2f + seat * 0.1f
            drawRoundRect(seatColor.copy(alpha = 0.85f), Offset(sx, sy), Size(seat, seat), CornerRadius(seat * 0.25f))
        }
    }
}

private fun DrawScope.drawEngines(
    specs: AircraftSpecs, x0: Float, len: Float, cy: Float, bw: Float,
    wingRoot: Float, sweep: Float, span: Float, tailLen: Float, ink: Color,
) {
    if (specs.kind == BodyKind.FIGHTER || specs.kind == BodyKind.LIGHT) return
    val podLen = len * 0.11f
    val podW = min(bw * 0.36f, (span - bw / 2) * 0.3f)  // four engines must fit on each half-span
    fun pod(cx: Float, cyPod: Float) {
        drawRoundRect(ink.copy(alpha = 0.16f), Offset(cx - podLen / 2, cyPod - podW / 2), Size(podLen, podW), CornerRadius(podW / 2))
        drawRoundRect(ink.copy(alpha = 0.45f), Offset(cx - podLen / 2, cyPod - podW / 2), Size(podLen, podW), CornerRadius(podW / 2), style = Stroke(1.dp.toPx()))
        if (specs.engineType != "turbofan") {  // propeller disc in front of the nacelle
            drawLine(ink.copy(alpha = 0.4f), Offset(cx - podLen / 2 - 2f, cyPod - podW * 1.3f), Offset(cx - podLen / 2 - 2f, cyPod + podW * 1.3f), 1.5.dp.toPx())
        }
    }
    val wingEngines = when {
        specs.kind == BodyKind.WIDE && specs.engines == 3 -> 2
        specs.tailEngines -> 0
        else -> specs.engines
    }
    // Under-wing engines, spread along each half-span; positioned slightly ahead of the leading edge.
    val perSide = wingEngines / 2
    for (side in listOf(-1f, 1f)) {
        for (i in 0 until perSide) {
            val frac = if (perSide == 1) 0.42f else 0.28f + i * 0.42f
            val y = cy + side * (bw / 2 + (span - bw / 2) * frac)
            val leading = wingRoot + sweep * ((y - cy) * side - bw / 2) / (span - bw / 2)
            pod(leading + podLen * 0.05f, y)
        }
    }
    // Rear-mounted engines beside the tail, plus a centreline engine on trijets.
    if (specs.tailEngines) {
        val x = x0 + len - tailLen * 1.05f
        if (specs.kind != BodyKind.WIDE) {
            pod(x, cy - bw / 2 - podW / 2)
            pod(x, cy + bw / 2 + podW / 2)
        }
        if (specs.engines == 3) {
            drawCircle(ink.copy(alpha = 0.25f), podW * 0.45f, Offset(x0 + len - tailLen * 0.55f, cy))
        }
    }
}
