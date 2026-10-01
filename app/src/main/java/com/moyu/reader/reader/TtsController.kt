package com.moyu.reader.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * 朗读控制器（TTS）。
 *
 * 用系统内建的 TextToSpeech，不引第三方朗读库：
 *   - 系统 TTS 支持中文引擎（各家 ROM 通常预装），无需下载模型；
 *   - 不需要网络、不需要额外权限，符合本地阅读器的定位。
 *
 * 状态机很简单但必须严格：idle → speaking → idle。
 * 之所以把「朗读中」暴露成 StateFlow 而不是回调：阅读器需要根据它切换按钮图标，
 * 而回调在 Compose 里需要手动转成状态，容易漏掉「朗读结束」这个事件。
 */
class TtsController(private val context: Context) {

    enum class State { IDLE, INITIALIZING, SPEAKING, UNAVAILABLE }

    private var tts: TextToSpeech? = null

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 正在朗读的文本片段（用于界面上高亮当前句）。 */
    private val _currentText = MutableStateFlow("")
    val currentText: StateFlow<String> = _currentText.asStateFlow()

    /** 一段读完后的回调（阅读器据此自动翻页继续朗读）。 */
    private var onFinishedCallback: (() -> Unit)? = null

    private var initialized = false
    private var pendingSpeak: Pair<String, Float>? = null
    private var utteranceCounter = 0

    /** 初始化（幂等）。系统 TTS 初始化是异步的，因此第一次调用可能不会立刻可用。 */
    fun initialize() {
        if (tts != null) return
        _state.value = State.INITIALIZING
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                initialized = true
                // 优先中文；部分设备没有中文引擎时退回默认语言，
                // 而不是直接判定不可用 —— 至少还能读英文内容。
                val result = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.getDefault())
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _state.value = State.SPEAKING
                    }

                    override fun onDone(utteranceId: String?) {
                        _state.value = State.IDLE
                        _currentText.value = ""
                        onFinishedCallback?.invoke()
                    }

                    @Deprecated("系统在 API 21 后不再调用此重载，但接口要求实现")
                    override fun onError(utteranceId: String?) {
                        _state.value = State.IDLE
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        _state.value = State.IDLE
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        _state.value = State.IDLE
                    }
                })
                pendingSpeak?.let { (text, rate) ->
                    pendingSpeak = null
                    speak(text, rate)
                }
            } else {
                _state.value = State.UNAVAILABLE
            }
        }
    }

    /** 当前设备是否可用。 */
    val isAvailable: Boolean get() = _state.value != State.UNAVAILABLE

    /**
     * 朗读一段文本。
     *
     * @param text 要朗读的文本（调用方应只传当前页，避免一次丢几万字给引擎）
     * @param rate 语速 0.5..2.0
     * @param pitch 音调 0..2
     * @param onFinished 读完后回调
     */
    fun speak(text: String, rate: Float = 1.0f, pitch: Float = 1.0f, onFinished: (() -> Unit)? = null) {
        if (text.isBlank()) return
        onFinishedCallback = onFinished

        if (!initialized) {
            initialize()
            // 初始化未完成：先记下来，等 onInit 回调后自动开读，避免用户第一次点朗读没反应
            pendingSpeak = text to rate
            return
        }

        val engine = tts ?: run {
            _state.value = State.UNAVAILABLE
            return
        }

        engine.setSpeechRate(rate.coerceIn(0.5f, 2f))
        engine.setPitch(pitch.coerceIn(0f, 2f))
        _currentText.value = text

        val utteranceId = "moyu_${++utteranceCounter}_${UUID.randomUUID().toString().take(6)}"
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            _state.value = State.IDLE
        } else {
            _state.value = State.SPEAKING
        }
    }

    /** 停止朗读。 */
    fun stop() {
        onFinishedCallback = null
        pendingSpeak = null
        tts?.stop()
        _state.value = State.IDLE
        _currentText.value = ""
    }

    fun setRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2f))
    }

    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch.coerceIn(0f, 2f))
    }

    /** 释放引擎。必须在 Activity 销毁时调用，否则会泄漏系统服务连接。 */
    fun shutdown() {
        onFinishedCallback = null
        pendingSpeak = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        initialized = false
        _state.value = State.IDLE
        _currentText.value = ""
    }
}
