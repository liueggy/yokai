package eu.kanade.tachiyomi.ui.reader.enhancement

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer

/** Probe before installation, keeping an incompatible import away from the reader. */
internal object OnnxModelValidator {
    fun validate(file: File): Int {
        val environment = OrtEnvironment.getEnvironment()
        return OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            environment.createSession(file.absolutePath, options).use { session ->
                require(session.numInputs == 1L && session.numOutputs == 1L)
                val info = session.inputInfo.values.single().info as? TensorInfo
                    ?: error("Expected an image tensor")
                require(info.type == OnnxJavaType.FLOAT)
                OnnxModelContract.validateInput(info.shape)
                val scale = probe(environment, session, 16, 16)
                require(probe(environment, session, 24, 32) == scale)
                scale
            }
        }
    }

    private fun probe(environment: OrtEnvironment, session: OrtSession, width: Int, height: Int): Int =
        OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(FloatArray(3 * width * height) { 0.5f }),
            longArrayOf(1, 3, height.toLong(), width.toLong()),
        ).use { input ->
            session.run(mapOf(session.inputNames.single() to input)).use { result ->
                val output = result[0] as? OnnxTensor ?: error("Expected image output")
                require(output.info.type == OnnxJavaType.FLOAT)
                val scale = OnnxModelContract.outputScale(output.info.shape, width, height)
                val values = requireNotNull(output.floatBuffer)
                while (values.hasRemaining()) require(values.get().isFinite())
                scale
            }
        }
}
