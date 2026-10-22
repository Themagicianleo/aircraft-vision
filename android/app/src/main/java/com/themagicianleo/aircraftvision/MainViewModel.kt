package com.themagicianleo.aircraftvision

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import kotlin.math.max

data class UiState(
    val image: Bitmap? = null,
    val predictions: List<Prediction> = emptyList(),
    val busy: Boolean = false,
    val inferenceMs: Long? = null,
    val error: String? = null,
    val supportedTypes: List<AircraftLabel> = emptyList(),
    /** The area being identified; starts as the centre square, then whatever the user drags it to. */
    val crop: CropBox? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val mutex = Mutex()
    private var classifier: AircraftClassifier? = null

    init {
        // Load the model up front: the first photo is analysed faster, and the type list is ready.
        viewModelScope.launch {
            try {
                val labels = withContext(Dispatchers.Default) { classifier().labels }
                _state.update { it.copy(supportedTypes = labels) }
            } catch (e: Exception) {
                _state.update { it.copy(error = describe(e)) }
            }
        }
    }

    /** A photo picked from the gallery or shared from another app. */
    fun onImage(uri: Uri) = analyse { withContext(Dispatchers.IO) { decode(uri) } }

    /** A frame frozen from the live camera. */
    fun onBitmap(bitmap: Bitmap) = analyse { bitmap }

    /** Back to the camera. */
    fun clear() {
        job?.cancel()
        _state.update { UiState(supportedTypes = it.supportedTypes) }
    }

    /** The user moved or resized the box: identify what's inside it now. */
    fun onCrop(box: CropBox) {
        val image = _state.value.image ?: return
        _state.update { it.copy(crop = box) }
        analyse { image }
    }

    private var job: Job? = null

    private fun analyse(load: suspend () -> Bitmap) {
        job?.cancel()
        _state.update { it.copy(busy = true, error = null, predictions = emptyList(), inferenceMs = null) }
        job = viewModelScope.launch {
            try {
                // Show the photo straight away, then analyse it.
                val bitmap = load()
                val box = _state.value.crop.takeIf { _state.value.image === bitmap }
                    ?: CropBox.centreSquare(bitmap.width, bitmap.height)
                _state.update { it.copy(image = bitmap, crop = box) }
                val (predictions, ms) = withContext(Dispatchers.Default) {
                    val clf = classifier()
                    val start = System.nanoTime()
                    clf.classify(cropRegion(bitmap, box)) to (System.nanoTime() - start) / 1_000_000
                }
                _state.update { it.copy(predictions = predictions, inferenceMs = ms, busy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Classification failed", e)
                _state.update { it.copy(busy = false, error = describe(e)) }
            }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is FileNotFoundException -> "Model not found. Copy aircraft.onnx and labels.json into app/src/main/assets and rebuild."
        else -> e.message ?: e.javaClass.simpleName
    }

    private suspend fun classifier(): AircraftClassifier = mutex.withLock {
        classifier ?: AircraftClassifier(getApplication()).also { classifier = it }
    }

    /** Decodes with EXIF rotation applied, capped at 1600 px so huge photos don't exhaust memory. */
    private fun decode(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > MAX_DECODE) {
                val scale = MAX_DECODE.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
        }.copy(Bitmap.Config.ARGB_8888, false)
    }

    override fun onCleared() {
        classifier?.close()
    }

    companion object {
        private const val TAG = "AircraftVision"
        private const val MAX_DECODE = 1600
    }
}
