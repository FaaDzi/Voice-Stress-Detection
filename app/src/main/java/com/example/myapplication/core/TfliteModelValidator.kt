package com.example.myapplication.core

import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.security.MessageDigest

object TfliteModelValidator {

    data class ValidationResult(
        val isValid: Boolean,
        val reason: String,
        val sha256: String? = null,
        val inputFeatureSize: Int? = null,
        val outputClassCount: Int? = null
    )

    fun validate(candidate: File, reference: File?): ValidationResult {
        if (!candidate.exists() || !candidate.isFile) {
            return ValidationResult(false, "Model file was not found.")
        }
        if (candidate.length() <= 0L) {
            return ValidationResult(false, "Model file is empty.")
        }

        val candidateSignature = try {
            readSignature(candidate)
        } catch (e: Exception) {
            return ValidationResult(false, "Model could not be opened by TensorFlow Lite.")
        }

        if (reference != null && reference.exists()) {
            val referenceSignature = try {
                readSignature(reference)
            } catch (e: Exception) {
                return ValidationResult(false, "Reference model signature could not be read.")
            }

            if (!candidateSignature.isCompatibleWith(referenceSignature)) {
                return ValidationResult(
                    isValid = false,
                    reason = "Model shape/type does not match the expected stress model signature."
                )
            }
        } else {
            if (candidateSignature.inputFeatureSize <= 0 || candidateSignature.outputClassCount <= 1) {
                return ValidationResult(false, "Model signature is incomplete or unsupported.")
            }
        }

        return ValidationResult(
            isValid = true,
            reason = "Model validated successfully.",
            sha256 = sha256(candidate),
            inputFeatureSize = candidateSignature.inputFeatureSize,
            outputClassCount = candidateSignature.outputClassCount
        )
    }

    private fun readSignature(file: File): ModelSignature {
        val options = Interpreter.Options().apply {
            setNumThreads(1)
        }
        Interpreter(file, options).use { interpreter ->
            interpreter.allocateTensors()
            val inputTensor = interpreter.getInputTensor(0)
            val outputTensor = interpreter.getOutputTensor(0)
            return ModelSignature(
                inputType = inputTensor.dataType(),
                outputType = outputTensor.dataType(),
                inputShape = inputTensor.shape().toList(),
                outputShape = outputTensor.shape().toList()
            )
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class ModelSignature(
        val inputType: DataType,
        val outputType: DataType,
        val inputShape: List<Int>,
        val outputShape: List<Int>
    ) {
        val inputFeatureSize: Int
            get() = inputShape.lastOrNull() ?: -1

        val outputClassCount: Int
            get() = outputShape.lastOrNull() ?: -1

        fun isCompatibleWith(reference: ModelSignature): Boolean {
            return inputType == reference.inputType &&
                outputType == reference.outputType &&
                inputFeatureSize == reference.inputFeatureSize &&
                outputClassCount == reference.outputClassCount
        }
    }
}
