package com.github.meetingrecorder.speakers

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

interface SpeakerEngine : AutoCloseable {
    fun segments(samples: FloatArray): List<SpeakerTurn>
    fun embedding(samples: FloatArray): List<Float>?
}

class SherpaSpeakerEngine(segmentation: File, embedding: File) : SpeakerEngine {
    private val embeddingConfig = SpeakerEmbeddingExtractorConfig(model = embedding.absolutePath, numThreads = 2, provider = "cpu")
    private val diarizer = OfflineSpeakerDiarization(
        config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = segmentation.absolutePath),
                numThreads = 2,
                provider = "cpu",
            ),
            embedding = embeddingConfig,
            clustering = FastClusteringConfig(numClusters = -1, threshold = 0.5f),
        ),
    )
    private val extractor = try {
        SpeakerEmbeddingExtractor(config = embeddingConfig)
    } catch (e: Exception) {
        diarizer.release()
        throw e
    }

    override fun segments(samples: FloatArray): List<SpeakerTurn> = diarizer.process(samples).map {
        SpeakerTurn((it.start * 1000).toLong(), (it.end * 1000).toLong(), it.speaker.toString())
    }

    override fun embedding(samples: FloatArray): List<Float>? {
        if (!sampleQuality(samples)) return null
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(samples, 16000)
            stream.inputFinished()
            if (extractor.isReady(stream)) normalizedEmbedding(extractor.compute(stream).toList()) else null
        } finally {
            stream.release()
        }
    }

    override fun close() {
        extractor.release()
        diarizer.release()
    }
}
