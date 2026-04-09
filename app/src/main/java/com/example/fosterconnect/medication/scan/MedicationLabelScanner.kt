package com.example.fosterconnect.medication.scan

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Thin wrapper around ML Kit's on-device Latin text recognizer.
 * Returns the raw recognized text via callback.
 */
class MedicationLabelScanner {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun scan(
        bitmap: Bitmap,
        onResult: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText -> onResult(visionText.text) }
            .addOnFailureListener { e -> onError(e) }
    }
}
