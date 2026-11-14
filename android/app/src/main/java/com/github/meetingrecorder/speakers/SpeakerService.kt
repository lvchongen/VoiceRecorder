package com.github.meetingrecorder.speakers

import com.github.meetingrecorder.data.AndroidAudioChunks
import com.github.meetingrecorder.data.AudioChunks
import com.github.meetingrecorder.data.NoSpeechDetectedException
import com.github.meetingrecorder.data.TranscriptionClient
import com.github.meetingrecorder.data.whisperSamples
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext

fun pcmWave(samples: FloatArray): ByteArray {
    val output = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    output.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVEfmt ".toByteArray())
    output.putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
    output.put("data".toByteArray()).putInt(samples.size * 2)
    samples.forEach { output.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
    return output.array()
}

private fun slice(samples: FloatArray, startMs: Long, endMs: Long): FloatArray {
    val start = (startMs.coerceAtLeast(0) * 16).coerceAtMost(samples.size.toLong()).toInt()
    val end = (endMs.coerceAtLeast(startMs) * 16).coerceIn(start.toLong(), samples.size.toLong()).toInt()
    return samples.copyOfRange(start, end)
}

/** Bounded native windows; identities and audition samples are isolated per meeting. */
class SpeakerService(
    val models: SpeakerModelStore,
    val repository: SpeakerRepository,
    private val chunks: AudioChunks = AndroidAudioChunks(),
    private val engineFactory: (File, File) -> SpeakerEngine = ::SherpaSpeakerEngine,
) {
    private val mutex = Mutex()

    suspend fun analyze(audio: File, meetingDir: File, onStatus: (String) -> Unit = {}): SpeakerMeeting = withContext(Dispatchers.IO) {
        mutex.withLock {
            val digest = MessageDigest.getInstance("SHA-256")
            audio.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            val sourceHash = digest.digest().joinToString("") { "%02x".format(it) }
            val previous = repository.readMeeting(meetingDir)
            previous?.let {
                if (it.sourceHash == sourceHash && it.pipelineVersion == SpeakerModelStore.PIPELINE_VERSION && it.speakers.flatMap { speaker -> speaker.samples }.all { sample -> sample.model == SpeakerModelStore.EMBEDDING.sha256 }) return@withLock it
            }
            models.withModels { segmentation, embedding ->
                val id = UUID.randomUUID().toString()
                val directory = repository.analysisDirectory(id)
                check(directory.mkdirs()) { "无法创建说话人分析目录" }
                var saved = false
                try {
                    val speakers = mutableListOf<MeetingSpeaker>()
                    val allTurns = mutableListOf<SpeakerTurn>()
                    engineFactory(segmentation, embedding).use { engine ->
                        chunks.read(audio) { chunk ->
                            coroutineContext.ensureActive()
                            onStatus("正在本地分人：${chunk.startMilliseconds / 1000} 秒…")
                            val pcm = whisperSamples(chunk.wav)
                            val duration = pcm.size / 16L
                            val raw = engine.segments(pcm).map { it.copy(startMs = it.startMs.coerceAtLeast(0), endMs = it.endMs.coerceAtMost(duration)) }
                            coroutineContext.ensureActive()
                            val clean = exclusiveTurns(raw)
                            val mapping = mutableMapOf<String, String?>()
                            val used = mutableSetOf<String>()
                            clean.filter { it.label != null }.mapNotNull { it.label }.distinct().forEach { local ->
                                val candidates = clean.filter { it.label == local && it.endMs - it.startMs >= 2000 }.sortedByDescending { it.endMs - it.startMs }
                                var voice: VoiceSample? = null
                                for (turn in candidates) {
                                    val clip = slice(pcm, turn.startMs, minOf(turn.endMs, turn.startMs + 6000))
                                    val vector = engine.embedding(clip) ?: continue
                                    voice = VoiceSample(embedding = vector, model = SpeakerModelStore.EMBEDDING.sha256, source = meetingDir.name, startMs = chunk.startMilliseconds + turn.startMs, durationMs = clip.size / 16L)
                                    File(directory, "${voice.id}.wav").writeBytes(pcmWave(clip))
                                    break
                                }
                                // Segmentation can create tiny spurious clusters. Without a usable
                                // voice sample there is no evidence for a new identity.
                                if (voice == null) {
                                    mapping[local] = null
                                    return@forEach
                                }
                                val scores = speakers.filter { it.label !in used }.mapNotNull { existing ->
                                    existing.samples.mapNotNull { cosineSimilarity(voice!!.embedding, it.embedding) }.maxOrNull()?.let { existing.label to it }
                                }.sortedByDescending { it.second }
                                val best = scores.firstOrNull()
                                // Conservative window stitching; these heuristic scores are not identity guarantees.
                                val reuse = best?.takeIf { it.second >= 0.65f && it.second - (scores.getOrNull(1)?.second ?: -1f) >= 0.08f }?.first
                                val label = reuse ?: speakerLabel(speakers.size)
                                if (reuse == null) speakers += MeetingSpeaker(label)
                                val index = speakers.indexOfFirst { it.label == label }
                                if (voice != null) {
                                    if (speakers[index].samples.size < 3) {
                                        speakers[index] = speakers[index].copy(samples = speakers[index].samples + voice)
                                    } else {
                                        File(directory, "${voice.id}.wav").delete()
                                    }
                                }
                                mapping[local] = label
                                used += label
                            }
                            allTurns += clean.map { it.copy(startMs = it.startMs + chunk.startMilliseconds, endMs = it.endMs + chunk.startMilliseconds, label = it.label?.let(mapping::getValue)) }
                        }
                    }
                    if (allTurns.isEmpty()) throw NoSpeechDetectedException("未检测到可用语音，请检查音频")
                    coroutineContext.ensureActive()
                    val result = SpeakerMeeting(id, audio.name, sourceHash, speakers, allTurns)
                    repository.saveMeeting(meetingDir, result)
                    saved = true
                    previous?.let { repository.removeAnalysis(it.id) }
                    result
                } finally {
                    if (!saved) directory.deleteRecursively()
                }
            }
        }
    }

    suspend fun analyzeAndEnroll(audio: File, meetingDir: File, onStatus: (String) -> Unit = {}): SpeakerMeeting {
        val meeting = analyze(audio, meetingDir, onStatus)
        return withContext(Dispatchers.IO) {
            try {
                repository.autoEnroll(meetingDir)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Voiceprint storage must not prevent transcription of a valid recording.
                meeting
            }
        }
    }

    suspend fun transcribe(audio: File, meetingDir: File, delegate: TranscriptionClient, onStatus: (String) -> Unit): String {
        val meeting = try {
            analyzeAndEnroll(audio, meetingDir, onStatus)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onStatus("声纹分析暂不可用，正在使用完整录音转写…")
            return delegate.transcribe(audio, "audio/${audio.extension}", onStatus)
        }
        return delegate.withSession(onStatus) { session ->
            try {
                withContext(Dispatchers.IO) {
                    val output = StringBuilder()
                    val temporary = File.createTempFile("speaker-turn-", ".wav", repository.analysisDirectory(meeting.id))
                    try {
                        chunks.read(audio) { chunk ->
                            val pcm = whisperSamples(chunk.wav)
                            val start = chunk.startMilliseconds
                            val end = start + pcm.size / 16L
                            meeting.turns.filter { it.startMs < end && it.endMs > start }.forEach { turn ->
                                coroutineContext.ensureActive()
                                val from = maxOf(start, turn.startMs)
                                val to = minOf(end, turn.endMs)
                                if (to - from < 100) return@forEach
                                temporary.writeBytes(pcmWave(slice(pcm, from - start, to - start)))
                                onStatus("正在转写 ${turn.label ?: "未确认"}：${from / 1000} 秒…")
                                val text = session.transcribe(temporary, "audio/wav", onStatus)
                                    .lineSequence().map { line -> cleanSpeakerTurnText(line) }
                                    .filter { it.isNotBlank() }.joinToString(" ")
                                if (text.isNotBlank()) {
                                    val seconds = from / 1000
                                    output.append("[%02d:%02d:%02d] **%s:** %s\n\n".format(Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60, turn.label ?: "未确认", text))
                                }
                            }
                        }
                        if (output.isBlank()) throw NoSpeechDetectedException("未识别到语音，请重试")
                        output.toString().trim()
                    } finally {
                        temporary.delete()
                    }
                }
            } catch (e: NoSpeechDetectedException) {
                onStatus("分人片段未识别到文字，正在使用完整录音重试…")
                session.transcribe(audio, "audio/${audio.extension}", onStatus)
            }
        }
    }
}

class SpeakerTranscriptionClient(private val service: SpeakerService, private val delegate: TranscriptionClient) : TranscriptionClient {
    override suspend fun transcribe(audioFile: File, mimeType: String, onStatus: (String) -> Unit): String =
        service.transcribe(audioFile, requireNotNull(audioFile.parentFile), delegate, onStatus)
}
