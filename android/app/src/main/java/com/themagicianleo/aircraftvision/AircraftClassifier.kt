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
import kotlin.math.roundToInt

data class AircraftLabel(val name: String, val family: String, val manufacturer: String)

data class Prediction(val label: AircraftLabel, val confidence: Float)

/**
 * Runs the exported ONNX model entirely on-device.
 *
 * The model takes RGB floats in [0,1] shaped [1,3,S,S] and returns softmax probabilities;
 * normalisation is baked into the model, so preprocessing here is just resize + centre crop,
 * matching training/av/mobile.py.
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

    /** Short side -> inputSize, centre crop, planar RGB floats. */
    private fun toModelInput(source: Bitmap): FloatBuffer {
        val s = inputSize
        val scaled = scaleShortSide(source, s)
        val x = (scaled.width - s) / 2
        val y = (scaled.height - s) / 2
        val pixels = IntArray(s * s)
        scaled.getPixels(pixels, 0, s, x, y, s, s)
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
    private fun scaleShortSide(source: Bitmap, target: Int): Bitmap {
        var bmp = source
        while (min(bmp.width, bmp.height) >= target * 2) {
            val next = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
            if (bmp !== source) bmp.recycle()
            bmp = next
        }
        val scale = target.toFloat() / min(bmp.width, bmp.height)
        val w = maxOf(target, (bmp.width * scale).roundToInt())
        val h = maxOf(target, (bmp.height * scale).roundToInt())
        if (w == bmp.width && h == bmp.height) return bmp
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
