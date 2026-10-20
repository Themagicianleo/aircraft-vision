package com.themagicianleo.aircraftvision

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
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
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val mutex = Mutex()
    private var classifier: AircraftClassifier? = null

    fun onImage(uri: Uri) {
        _state.update { it.copy(busy = true, error = null, predictions = emptyList()) }
        viewModelScope.launch {
            try {
                val (bitmap, predictions, ms) = withContext(Dispatchers.Default) {
                    val bitmap = decode(uri)
                    val clf = classifier()
                    val start = System.nanoTime()
                    val predictions = clf.classify(bitmap)
                    Triple(bitmap, predictions, (System.nanoTime() - start) / 1_000_000)
                }
                _state.update { UiState(image = bitmap, predictions = predictions, inferenceMs = ms) }
            } catch (e: FileNotFoundException) {
                _state.update { it.copy(busy = false, error = "Model not found. Copy aircraft.onnx and labels.json into app/src/main/assets and rebuild.") }
            } catch (e: Exception) {
                Log.e(TAG, "Classification failed", e)
                _state.update { it.copy(busy = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
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
