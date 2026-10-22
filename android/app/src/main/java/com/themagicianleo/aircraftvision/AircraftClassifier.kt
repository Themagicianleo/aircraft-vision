package com.themagicianleo.aircraftvision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.min

data class AircraftLabel(val name: String, val family: String, val manufacturer: String)

data class Prediction(val label: AircraftLabel, val confidence: Float)

/** A box in image coordinates normalised to 0..1. */
data class CropBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object {
        /** The centre square: what the model sees when nobody draws a box. */
        fun centreSquare(width: Int, height: Int): CropBox {
            val side = min(width, height).toFloat()
            val x = (width - side) / 2 / width
            val y = (height - side) / 2 / height
            return CropBox(x, y, 1 - x, 1 - y)
        }
    }
}

/**
 * Cuts [box] out of [bitmap], first widening it if needed so its aspect ratio stays within
 * what training's random crops covered (about 3:5 to 5:3); a squashed sliver would confuse the model.
 */
fun cropRegion(bitmap: Bitmap, box: CropBox): Bitmap {
    var l = box.left * bitmap.width
    var t = box.top * bitmap.height
    var r = box.right * bitmap.width
    var b = box.bottom * bitmap.height
    val maxAspect = 1.66f
    if ((r - l) / (b - t) > maxAspect) {
        val grow = ((r - l) / maxAspect - (b - t)) / 2
        t = (t - grow).coerceAtLeast(0f)
        b = (b + grow).coerceAtMost(bitmap.height.toFloat())
    } else if ((b - t) / (r - l) > maxAspect) {
        val grow = ((b - t) / maxAspect - (r - l)) / 2
        l = (l - grow).coerceAtLeast(0f)
        r = (r + grow).coerceAtMost(bitmap.width.toFloat())
    }
    val x = l.toInt().coerceIn(0, bitmap.width - 1)
    val y = t.toInt().coerceIn(0, bitmap.height - 1)
    val w = (r.toInt() - x).coerceIn(1, bitmap.width - x)
    val h = (b.toInt() - y).coerceIn(1, bitmap.height - y)
    return Bitmap.createBitmap(bitmap, x, y, w, h)
}

/**
 * Runs the exported ONNX model entirely on-device.
 *
 * The model takes RGB floats in [0,1] shaped [1,3,S,S] and returns softmax probabilities;
 * normalisation is baked into the model, so preprocessing here is just a resize of the region
 * to analyse (see [cropRegion]), matching training/av/mobile.py for the default centre square.
 */
class AircraftClassifier(context: Context) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    val labels: List<AircraftLabel>
    val inputSize: Int

    init {
        val json = JSONObject(context.assets.open(LABELS_FILE).bufferedReader().use { it.readText() })
        inputSize = json.getInt("img_size")
        val classes = json.getJSONArray("classes")
        labels = List(classes.length()) { i ->
            val c = classes.getJSONObject(i)
            AircraftLabel(c.getString("name"), c.optString("family"), c.optString("manufacturer"))
        }

        val modelBytes = context.assets.open(MODEL_FILE).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(min(4, Runtime.getRuntime().availableProcessors()))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(modelBytes, options)
        inputName = session.inputNames.first()
    }

    fun classify(bitmap: Bitmap, topK: Int = 5): List<Prediction> {
        val input = toModelInput(bitmap)
        val probs = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                (result[0].value as Array<FloatArray>)[0]
            }
        }
        return probs.indices
            .sortedByDescending { probs[it] }
            .take(topK)
            .map { Prediction(labels[it], probs[it]) }
    }

    /**
     * Resizes the given region straight to inputSize x inputSize (planar RGB floats).
     * Callers pass the area to analyse: by default the centre square, which matches training's
     * eval transform; a user-drawn box may be non-square, which training's random crops also cover.
     */
    private fun toModelInput(source: Bitmap): FloatBuffer {
        val s = inputSize
        val scaled = scaleTo(source, s, s)
        val pixels = IntArray(s * s)
        scaled.getPixels(pixels, 0, s, 0, 0, s, s)
        if (scaled !== source) scaled.recycle()

        val plane = s * s
        val buffer = FloatBuffer.allocate(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            buffer.put(i, ((p shr 16) and 0xFF) / 255f)
            buffer.put(plane + i, ((p shr 8) and 0xFF) / 255f)
            buffer.put(2 * plane + i, (p and 0xFF) / 255f)
        }
        return buffer
    }

    /**
     * Downscale by repeated halving first: a single bilinear step from a 12 MP photo
     * aliases badly, whereas the training pipeline (PIL) filters properly.
     */
    private fun scaleTo(source: Bitmap, w: Int, h: Int): Bitmap {
        var bmp = source
        while (bmp.width >= w * 2 && bmp.height >= h * 2) {
            val next = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
            if (bmp !== source) bmp.recycle()
            bmp = next
        }
        if (bmp.width == w && bmp.height == h) return bmp
        val out = Bitmap.createScaledBitmap(bmp, w, h, true)
        if (bmp !== source) bmp.recycle()
        return out
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val MODEL_FILE = "aircraft.onnx"
        const val LABELS_FILE = "labels.json"
    }
}
