package com.csdcorp.speech_to_text

import android.Manifest
import android.R.attr.data
import android.annotation.TargetApi
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.*
import android.speech.SpeechRecognizer.createOnDeviceSpeechRecognizer
import android.speech.SpeechRecognizer.createSpeechRecognizer
import android.util.Log
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import java.util.concurrent.Executors
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder



enum class SpeechToTextErrors {
    multipleRequests,
    unimplemented,
    noLanguageIntent,
    recognizerNotAvailable,
    missingOrInvalidArg,
    missingContext,
    unknown
}

enum class SpeechToTextCallbackMethods {
    textRecognition,
    notifyStatus,
    notifyError,
    soundLevelChange,
}

enum class SpeechToTextStatus {
    listening,
    notListening,
    unavailable,
    available,
    done,
    doneNoResult,
}

enum class ListenMode {
    deviceDefault,
    dictation,
    search,
    confirmation,
}

const val pluginChannelName = "plugin.csdcorp.com/speech_to_text"

@TargetApi(8)
/** SpeechToTextPlugin */
public class SpeechToTextPlugin :
    MethodCallHandler, RecognitionListener,
    PluginRegistry.RequestPermissionsResultListener, FlutterPlugin,
    ActivityAware {
    private var pluginContext: Context? = null
    private var channel: MethodChannel? = null
    private val minSdkForSpeechSupport = 21
    private val brokenStopSdk = 29
    private val minSdkForOnDeviceSpeechSupport = 31
    private val speechToTextPermissionCode = 28521
    private val missingConfidence: Double = -1.0
    private var speechThresholdRms = 9
    private val logTag = "SpeechToTextPlugin"
    private var recognizerStops = true
    private var currentActivity: Activity? = null
    private var activeResult: Result? = null
    private var initializedSuccessfully: Boolean = false
    private var permissionToRecordAudio: Boolean = false
    private var listening = false
    private var debugLogging: Boolean = false
    private var alwaysUseStop: Boolean = false
    private var intentLookup: Boolean = false
    private var noBluetoothOpt: Boolean = false // user-defined option
    private var bluetoothDisabled = true // final bluetooth state (combines user-defined option and permissions)
    private var resultSent: Boolean = false
    private var lastOnDevice: Boolean = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private var bluetoothAdapter: android.bluetooth.BluetoothAdapter? = null
    private var pairedDevices: Set<android.bluetooth.BluetoothDevice>? = null
    private var activeBluetooth: android.bluetooth.BluetoothDevice? = null
    private var bluetoothHeadset: BluetoothHeadset? = null
    private var previousRecognizerLang: String? = null
    private var previousPartialResults: Boolean = true
    private var previousListenMode: ListenMode = ListenMode.deviceDefault
    private var lastFinalTime: Long = 0
    private var speechStartTime: Long = 0
    private var minRms: Float = 1000.0F
    private var maxRms: Float = -100.0F
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val defaultLanguageTag: String = Locale.getDefault().toLanguageTag()

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {

        onAttachedToEngine(flutterPluginBinding.getApplicationContext(), flutterPluginBinding.getBinaryMessenger());
    }

    // 버퍼 못받는 문제로 따로 생성

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val bufferSize = AudioRecord.getMinBufferSize(
        16000,  // Sample rate in Hz
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    private val audioDataBuffer = ByteArray(bufferSize)

    // Method to start the AudioRecord session
    private fun startAudioRecording() {
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000, // 샘플 레이트
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("SpeechToTextPlugin", "AudioRecord initialization failed")
            return
        }

        audioRecord?.startRecording()
        isRecording = true

        // Start a background thread to read the audio data
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            while (isRecording) {
                val readBytes = audioRecord?.read(audioDataBuffer, 0, bufferSize) ?: 0
                if (readBytes > 0) {
                    synchronized(bufferList) {
                        bufferList.add(audioDataBuffer.copyOf(readBytes))
                    }
                    Log.d("SpeechToTextPlugin", "Captured audio data of size: $readBytes bytes")
                } else {
                    Log.w("SpeechToTextPlugin", "No audio data captured")
                }
            }
        }.start()


        Log.d("SpeechToTextPlugin", "Audio recording started with buffer size: $bufferSize bytes")
    }

    // Method to stop the AudioRecord session
    private fun stopAudioRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        if (bufferList.isNotEmpty()) {
            val combinedBuffer = mergeBuffers(bufferList)
            bufferList.clear()
            channel?.invokeMethod("onVoiceDataReceived", combinedBuffer)
            Log.d(logTag, "Audio data successfully captured and sent to Flutter")
        } else {
            Log.w(logTag, "No audio data recorded during session")
        }
    }

    // 끝


    private fun onAttachedToEngine(applicationContext: Context, messenger: BinaryMessenger) {
        this.pluginContext = applicationContext;
        channel = MethodChannel(messenger, pluginChannelName)
        channel?.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        this.pluginContext = null;
        channel?.setMethodCallHandler(null)
        channel = null
    }

    override fun onDetachedFromActivity() {
        currentActivity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        currentActivity = null
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull rawrResult: Result) {
        Log.d("SpeechToTextPlugin", "Method called: ${call.method}")
        val result = ChannelResultWrapper(rawrResult)
        try {
            when (call.method) {
                "has_permission" -> hasPermission(result)
                "initialize" -> {
                    var dlog = call.argument<Boolean>("debugLogging")
                    if (null != dlog) {
                        debugLogging = dlog
                    }
                    var ausOpt = call.argument<Boolean>("alwaysUseStop")
                    if (null != ausOpt) {
                        alwaysUseStop = ausOpt == true
                    }
                    var iOpt = call.argument<Boolean>("intentLookup")
                    if (null != iOpt) {
                        intentLookup = iOpt == true
                    }
                    var noBtOpt = call.argument<Boolean>("noBluetooth")
                    if (null != noBtOpt) {
                        noBluetoothOpt = noBtOpt == true
                    }
                    initialize(result)
                }
                "listen" -> {
                    var localeId = call.argument<String>("localeId")
                    if (null == localeId) {
                        localeId = defaultLanguageTag
                    }
                    localeId = localeId.replace( '_', '-')
                    var partialResults = call.argument<Boolean>("partialResults")
                    if (null == partialResults) {
                        partialResults = true
                    }
                    var onDevice = call.argument<Boolean>("onDevice")
                    if ( null == onDevice ) {
                        onDevice = false
                    }
                    val listenModeIndex = call.argument<Int>("listenMode")
                    if ( null == listenModeIndex ) {
                        result.error(SpeechToTextErrors.missingOrInvalidArg.name,
                            "listenMode is required", null)
                        return
                    }
                    startListening(result, localeId, partialResults, listenModeIndex, onDevice )
                }
                "stop" -> stopListening(result)
                "cancel" -> cancelListening(result)
                "locales" -> locales(result)
                "getAudioBuffer" -> {
                    val combinedBuffer = mergeBuffers(bufferList)  // bufferList를 결합하여 ByteArray로 변환
                    result.success(combinedBuffer)  // 결합된 ByteArray를 Flutter로 반환
                }
                else -> result.notImplemented()
            }
        } catch (exc: Exception) {
            Log.e(logTag, "Unexpected exception", exc)
            result.error(SpeechToTextErrors.unknown.name,
                "Unexpected exception", exc.localizedMessage)
        }
    }

    private var isListening: Boolean = false

    private fun hasPermission(result: Result) {
        if (sdkVersionTooLow()) {
            result.success(false)
            return
        }
        debugLog("Start has_permission")
        val localContext = pluginContext
        if (localContext != null) {
            val hasPerm = ContextCompat.checkSelfPermission(localContext,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            result.success(hasPerm)
        }
    }

    private fun initialize(result: Result) {
        if (sdkVersionTooLow()) {
            result.success(false)
            return
        }
        recognizerStops = Build.VERSION.SDK_INT != brokenStopSdk || alwaysUseStop
        debugLog("Start initialize")
        if (null != activeResult) {
            result.error(SpeechToTextErrors.multipleRequests.name,
                "Only one initialize at a time", null)
            return
        }
        activeResult = result
        initializeIfPermitted(pluginContext)
    }

    private fun isNotInitialized(): Boolean {
        if (!initializedSuccessfully) {
            Log.d("SpeechToTextPlugin", "Speech recognition is not initialized")
        }
        return !initializedSuccessfully
    }



    private fun isNotListening(): Boolean {
        return !listening
    }

    // startListening 함수 수정
    private fun startListening(result: Result, languageTag: String, partialResults: Boolean,
                               listenModeIndex: Int, onDevice: Boolean) {
        if (sdkVersionTooLow() || isNotInitialized() || isListening) {
            result.success(false)
            Log.d("SpeechToTextPlugin", "Speech recognition not started: SDK too low or already listening")
            return
        }

        Log.d("SpeechToTextPlugin", "Starting speech recognition")

        // Stop AudioRecord before starting SpeechRecognizer to avoid conflicts
        stopAudioRecording() // 음성 인식 전에 AudioRecord를 중지

        resultSent = false
        createRecognizer(onDevice)
        minRms = 1000.0F
        maxRms = -100.0F

        var listenMode = ListenMode.deviceDefault
        if (listenModeIndex == ListenMode.dictation.ordinal) {
            listenMode = ListenMode.dictation
        }

        optionallyStartBluetooth()
        setupRecognizerIntent(languageTag, partialResults, listenMode, onDevice)

        handler.post {
            run {
                speechRecognizer?.startListening(recognizerIntent)
                Log.d("SpeechToTextPlugin", "Speech recognition started successfully")
                isListening = true
            }
        }

        speechStartTime = System.currentTimeMillis()
        notifyListening(isRecording = true)
        result.success(true)
    }


    private fun optionallyStartBluetooth() {
        if ( bluetoothDisabled ) return
        val context = pluginContext
        val lbt = bluetoothAdapter
        val lpaired = pairedDevices
        val lhead = bluetoothHeadset
        if (null != lbt && null!= lhead && null != lpaired && lbt.isEnabled) {
            for (tryDevice in lpaired) {
                //This loop tries to start VoiceRecognition mode on every paired device until it finds one that works(which will be the currently in use bluetooth headset)
                if (lhead.startVoiceRecognition(tryDevice)) {
                    debugLog("Starting bluetooth voice recognition")
                    activeBluetooth = tryDevice;
                    break
                }
            }
        }
    }

    private fun sdkVersionTooLow(): Boolean {
        if (Build.VERSION.SDK_INT < minSdkForSpeechSupport) {
            Log.d("SpeechToTextPlugin", "SDK version is too low for speech recognition")
            return true
        }
        return false
    }


    // stopListening 함수 수정
    private fun stopListening(result: Result) {
        if (sdkVersionTooLow() || isNotInitialized() || !isListening) {
            result.success(false)
            Log.d("SpeechToTextPlugin", "Cannot stop speech recognition: not initialized or not listening")
            return
        }

        debugLog("Stop listening")
        handler.post {
            run {
                speechRecognizer?.stopListening()
                Log.d("SpeechToTextPlugin", "Speech recognition stopped successfully")
                isListening = false
                startAudioRecording() // 음성 인식 후 AudioRecord를 다시 활성화
            }
        }

        if (!recognizerStops) {
            destroyRecognizer()
        }

        notifyListening(isRecording = false)
        result.success(true)
        debugLog("Stop listening done")
    }

    private fun cancelListening(result: Result) {
        if (sdkVersionTooLow() || isNotInitialized() || isNotListening()) {
            result.success(false)
            return
        }
        debugLog("Cancel listening")
        handler.post {
            run {
                speechRecognizer?.cancel()
            }
        }
        if ( !recognizerStops ) {
            destroyRecognizer()
        }
        notifyListening(isRecording = false)
        result.success(true)
        debugLog("Cancel listening done")
    }

    private fun locales(result: Result) {
        if (sdkVersionTooLow()) {
            result.success(false)
            return
        }
        var hasPermission = ContextCompat.checkSelfPermission(pluginContext!!,
            Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 33 && hasPermission) {
            if ( SpeechRecognizer.isOnDeviceRecognitionAvailable(pluginContext!!)) {
                // after much experimentation this was the only working iteration of the
                // checkRecognitionSupport that works.
                var recognizer = createOnDeviceSpeechRecognizer(pluginContext!!)
                var recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
//            var recognizer = createSpeechRecognizer(pluginContext!!)
//            var recognizerIntent = Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS)
                recognizer?.checkRecognitionSupport(recognizerIntent, Executors.newSingleThreadExecutor(),
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                            var details = LanguageDetailsChecker( result, debugLogging )
                            details.createResponse(recognitionSupport.supportedOnDeviceLanguages )
                            recognizer?.destroy()
                        }
                        override fun onError(error: Int) {
                            debugLog("error from checkRecognitionSupport: " + error)
                            recognizer?.destroy()
                        }
                    })
            }
        } else {
            var detailsIntent = RecognizerIntent.getVoiceDetailsIntent(pluginContext)
            if (null == detailsIntent) {
                detailsIntent = Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS)
                detailsIntent.setPackage("com.google.android.googlequicksearchbox")
            }
            pluginContext?.sendOrderedBroadcast(
                detailsIntent, null, LanguageDetailsChecker(result, debugLogging),
                null, Activity.RESULT_OK, null, null)
        }
    }

    private fun notifyListening(isRecording: Boolean ) {
        if ( listening == isRecording ) return;
        listening = isRecording
        val status = when (isRecording) {
            true -> SpeechToTextStatus.listening.name
            false -> SpeechToTextStatus.notListening.name
        }
        debugLog("Notify status:" + status)
        channel?.invokeMethod(SpeechToTextCallbackMethods.notifyStatus.name, status)
        if ( !isRecording ) {
            val doneStatus = when( resultSent) {
                false -> SpeechToTextStatus.doneNoResult.name
                else -> SpeechToTextStatus.done.name
            }
            debugLog("Notify status:" + doneStatus )
            optionallyStopBluetooth();
            channel?.invokeMethod(SpeechToTextCallbackMethods.notifyStatus.name,
                doneStatus )
        }
    }

    private fun optionallyStopBluetooth() {
        if ( bluetoothDisabled ) return
        val lactive = activeBluetooth
        val lbt = bluetoothHeadset
        if (null != lactive && null != lbt ) {
            debugLog("Stopping bluetooth voice recognition")
            lbt.stopVoiceRecognition(lactive)
            activeBluetooth = null
        }
    }

    private fun updateResults(speechBundle: Bundle?, isFinal: Boolean) {
        if (isDuplicateFinal(isFinal)) {
            debugLog("Discarding duplicate final")
            return
        }
        val userSaid = speechBundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (userSaid != null && userSaid.isNotEmpty()) {
            val speechResult = JSONObject()
            speechResult.put("finalResult", isFinal)
            val confidence = speechBundle.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            val alternates = JSONArray()
            for (resultIndex in userSaid.indices) {
                val speechWords = JSONObject()
                speechWords.put("recognizedWords", userSaid[resultIndex])
                if (confidence != null && confidence.size > resultIndex) {
                    speechWords.put("confidence", confidence[resultIndex])
                } else {
                    speechWords.put("confidence", missingConfidence)
                }
                alternates.put(speechWords)
            }
            speechResult.put("alternates", alternates)
            val jsonResult = speechResult.toString()
            debugLog("Calling results callback with result: $jsonResult")
            resultSent = true

            // Flutter로 결과 전달
            handler.post {
                channel?.invokeMethod(SpeechToTextCallbackMethods.textRecognition.name, jsonResult)
            }
        } else {
            debugLog("Results null or empty")
        }
    }


    private fun isDuplicateFinal( isFinal: Boolean ) : Boolean {
        if ( !isFinal ) {
            return false
        }
        val delta = System.currentTimeMillis() - lastFinalTime
        lastFinalTime = System.currentTimeMillis()
        return delta >= 0 && delta < 100
    }

    private fun initializeIfPermitted(context: Context?) {
        val localContext = context
        if (null == localContext) {
            completeInitialize()
            return
        }
        permissionToRecordAudio = ContextCompat.checkSelfPermission(localContext,
            Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!permissionToRecordAudio) {
            Log.d("SpeechToTextPlugin", "Requesting audio permission")  // 로그 추가
            val localActivity = currentActivity
            if (null != localActivity) {
                ActivityCompat.requestPermissions(localActivity, arrayOf(Manifest.permission.RECORD_AUDIO), speechToTextPermissionCode)
            }
        } else {
            Log.d("SpeechToTextPlugin", "Audio permission already granted")  // 로그 추가
            completeInitialize()
        }
    }

    private fun completeInitialize() {

        debugLog("completeInitialize")
        if (permissionToRecordAudio) {
            debugLog("Testing recognition availability")
            val localContext = pluginContext
            if (localContext != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (!SpeechRecognizer.isRecognitionAvailable(localContext) && !SpeechRecognizer.isOnDeviceRecognitionAvailable(
                            localContext
                        )
                    ) {
                        Log.e(logTag, "Speech recognition not available on this device")
                        activeResult?.error(
                            SpeechToTextErrors.recognizerNotAvailable.name,
                            "Speech recognition not available on this device", ""
                        )
                        activeResult = null
                        return
                    }
                } else {
                    if (!SpeechRecognizer.isRecognitionAvailable(localContext)) {
                        Log.e(logTag, "Speech recognition not available on this device")
                        activeResult?.error(
                            SpeechToTextErrors.recognizerNotAvailable.name,
                            "Speech recognition not available on this device", ""
                        )
                        activeResult = null
                        return
                    }
                }
                setupBluetooth()
            } else {
                debugLog("null context during initialization")
                activeResult?.success(false)
                activeResult?.error(
                    SpeechToTextErrors.missingContext.name,
                    "context unexpectedly null, initialization failed", "")
                activeResult = null
                return
            }
        }

        initializedSuccessfully = permissionToRecordAudio
        debugLog("sending result")
        activeResult?.success(permissionToRecordAudio)
        debugLog("leaving complete")
        activeResult = null
    }

    private fun setupBluetooth() {
        if ( bluetoothDisabled ) return
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        pairedDevices = bluetoothAdapter?.getBondedDevices()

        val mProfileListener: BluetoothProfile.ServiceListener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    bluetoothHeadset = proxy as BluetoothHeadset
                    debugLog("Found a headset: " + bluetoothHeadset.toString())
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HEADSET) {
                    debugLog("Clearing headset: ")
                    bluetoothHeadset = null
                }
            }
        }
        bluetoothAdapter?.getProfileProxy(pluginContext, mProfileListener, BluetoothProfile.HEADSET)
    }

    private fun Context.findComponentName(): ComponentName? {
        val list: List<ResolveInfo> = packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        debugLog("RecognitionService, found: ${list.size}")
        list.forEach() { it.serviceInfo?.let { it1 -> debugLog("RecognitionService: packageName: ${it1.packageName}, name: ${it1.name}") } }
        return list.firstOrNull()?.serviceInfo?.let { ComponentName(it.packageName, it.name) }
    }

    private fun createRecognizer(onDevice: Boolean) {
        if ( null != speechRecognizer && onDevice == lastOnDevice ) {
            return
        }
        lastOnDevice = onDevice
        speechRecognizer?.destroy()
        speechRecognizer = null
        handler.post {
            run {
                debugLog("Creating recognizer")
                if (intentLookup) {
                    speechRecognizer = createSpeechRecognizer(
                        pluginContext,
                        pluginContext?.findComponentName()
                    ).apply {
                        debugLog("Setting listener after intent lookup")
                        setRecognitionListener(this@SpeechToTextPlugin)
                    }
                } else {
                    var supportsLocal = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && onDevice) {
                        supportsLocal = SpeechRecognizer.isOnDeviceRecognitionAvailable(pluginContext!!)
                        if (supportsLocal ) {
                            speechRecognizer = createOnDeviceSpeechRecognizer(pluginContext!!).apply {
                                debugLog("Setting on device listener")
                                setRecognitionListener(this@SpeechToTextPlugin)
                            }
                        }
                    }
                    if ( null == speechRecognizer) {
                        speechRecognizer = createSpeechRecognizer(pluginContext).apply {
                            debugLog("Setting default listener")
                            setRecognitionListener(this@SpeechToTextPlugin)
                        }
                    }
                }
                if (null == speechRecognizer) {
                    Log.e(logTag, "Speech recognizer null")
                    activeResult?.error(
                        SpeechToTextErrors.recognizerNotAvailable.name,
                        "Speech recognizer null", ""
                    )
                    activeResult = null
                }
            }
        }
        debugLog("before setup intent")
        setupRecognizerIntent(defaultLanguageTag, true, ListenMode.deviceDefault, false )
        debugLog("after setup intent")
    }

    private fun setupRecognizerIntent(languageTag: String, partialResults: Boolean, listenMode: ListenMode, onDevice: Boolean ) {
        debugLog("setupRecognizerIntent")
        if (previousRecognizerLang == null ||
            previousRecognizerLang != languageTag ||
            partialResults != previousPartialResults || previousListenMode != listenMode ) {
            previousRecognizerLang = languageTag;
            previousPartialResults = partialResults
            previousListenMode = listenMode
            handler.post {
                run {
                    recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        debugLog("In RecognizerIntent apply")
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        debugLog("put model")
                        val localContext = pluginContext
                        if (null != localContext) {
                            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE,
                                localContext.applicationInfo.packageName)
                        }
                        debugLog("put package")
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialResults)
                        debugLog("put partial results: $partialResults")
                        if (languageTag != Locale.getDefault().toLanguageTag()) {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                            debugLog("put languageTag: $languageTag")
                        }
                        if (onDevice) {
                            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
                            debugLog("put prefer offline: $onDevice")
                        }
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10)
                    }
                }
            }
        }
    }

    private fun destroyRecognizer() {

        handler.postDelayed( {
            run {
                debugLog("Recognizer destroy")
                speechRecognizer?.destroy();
                speechRecognizer = null;
            }
        }, 50 )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        when (requestCode) {
            speechToTextPermissionCode -> {
                permissionToRecordAudio = grantResults.isNotEmpty() &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED
                bluetoothDisabled = (grantResults.isEmpty() || grantResults.size == 1 ||
                        grantResults[1] != PackageManager.PERMISSION_GRANTED) ||
                        noBluetoothOpt
                completeInitialize()
                return true
            }
        }
        return false
    }


    // 수정
    override fun onPartialResults(results: Bundle?) {
        Log.d("SpeechToTextPlugin", "onPartialResults called") // 로그 추가
        updateResults(results, false)
    }

    override fun onResults(results: Bundle?) {
        Log.d("SpeechToTextPlugin", "onResults called") // 로그 추가
        updateResults(results, true)

        // AudioRecord에서 수집된 데이터를 중단하고 전송
        stopAudioRecording()
    }

    private val bufferList: MutableList<ByteArray> = mutableListOf()

    // 음성 인식 종료 시 로그 출력
    override fun onEndOfSpeech() {
        Log.d("SpeechToTextPlugin", "onEndOfSpeech called, Buffer list size: ${bufferList.size}")

        if (bufferList.isNotEmpty()) {
            handler.post {
                val combinedBuffer = mergeBuffers(bufferList)
                Log.d("SpeechToTextPlugin", "Combined buffer size: ${combinedBuffer.size} bytes") // 결합된 버퍼 크기 로그 추가
                channel?.invokeMethod("onVoiceDataReceived", combinedBuffer)
                bufferList.clear() // 버퍼 리스트 초기화
            }
        } else {
            Log.w("SpeechToTextPlugin", "Buffer list is empty at end of speech, no audio data collected") // 음성 데이터가 없을 때 경고
        }
    }



    // ByteArray 리스트를 하나로 결합
    private fun mergeBuffers(bufferList: MutableList<ByteArray>): ByteArray {
        if (bufferList.isEmpty()) {
            Log.w(logTag, "Buffer list is empty, returning an empty ByteArray") // 빈 버퍼 리스트 경고 로그
            return ByteArray(0)
        }

        val outputStream = java.io.ByteArrayOutputStream()
        return try {
            for (buffer in bufferList) {
                Log.d(logTag, "Merging buffer of size: ${buffer.size} bytes") // 각 버퍼의 크기 로그 추가
                outputStream.write(buffer)
            }
            outputStream.toByteArray() // 결합된 데이터 반환
        } catch (e: IOException) {
            Log.e(logTag, "Error merging buffers: ${e.message}")
            ByteArray(0) // 오류 발생 시 빈 배열 반환
        } finally {
            try {
                outputStream.close() // 스트림 닫기
            } catch (e: IOException) {
                Log.e(logTag, "Error closing ByteArrayOutputStream: ${e.message}")
            }
        }
    }

    override fun onError(errorCode: Int) {
        Log.d("SpeechToTextPlugin", "onError called with errorCode: $errorCode")

        val errorMsg = when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "error_audio_error"
            SpeechRecognizer.ERROR_CLIENT -> "error_client"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "error_permission"
            SpeechRecognizer.ERROR_NETWORK -> "error_network"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "error_network_timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "error_no_match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "error_busy"
            SpeechRecognizer.ERROR_SERVER -> "error_server"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "error_speech_timeout"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "error_language_not_supported"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "error_language_unavailable"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "error_server_disconnected"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "error_too_many_requests"
            else -> "error_unknown ($errorCode)"
        }

        Log.d("SpeechToTextPlugin", "Error message: $errorMsg")
        sendError(errorMsg)

        // AudioRecord 재시작 (SpeechRecognizer가 오류를 반환했으므로, AudioRecord를 재사용 가능)
        startAudioRecording()

        handler.post {
            channel?.invokeMethod(SpeechToTextCallbackMethods.notifyError.name, errorMsg)
        }

        if (isListening) {
            notifyListening(false)
            isListening = false
        }
    }


    private fun debugLog( msg: String ) {
        if ( debugLogging ) {
            Log.d( logTag, msg )
        }
    }

    private fun sendError(errorMsg: String) {
        val speechError = JSONObject()
        speechError.put("errorMsg", errorMsg)
        speechError.put("permanent", true)
        handler.post {
            run {
                channel?.invokeMethod(SpeechToTextCallbackMethods.notifyError.name, speechError.toString())
            }
        }
    }

    override fun onRmsChanged(rmsdB: Float) {
        if ( rmsdB < minRms ) {
            minRms = rmsdB
        }
        if ( rmsdB > maxRms ) {
            maxRms = rmsdB
        }
        debugLog("rmsDB $minRms / $maxRms")
        handler.post {
            run {
                channel?.invokeMethod(SpeechToTextCallbackMethods.soundLevelChange.name, rmsdB)
            }
        }
    }

    // 수정
    private var recordedFilePath: String? = null

    // 권한 확인 로직 추가
    private fun checkStoragePermission(): Boolean {
        val localContext = pluginContext ?: return false
        return ContextCompat.checkSelfPermission(localContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    // 권한 요청 로직
    private fun requestStoragePermission() {
        val localActivity = currentActivity ?: return
        ActivityCompat.requestPermissions(localActivity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), speechToTextPermissionCode)
    }


    override fun onReadyForSpeech(p0: Bundle?) {
        Log.d("SpeechToTextPlugin", "onReadyForSpeech called - buffer list cleared")
        bufferList.clear() // 새로운 인식 시작 시 버퍼 초기화
    }

    override fun onBufferReceived(buffer: ByteArray?) {
        if (buffer != null && buffer.isNotEmpty()) {
            bufferList.add(buffer)
            Log.d("SpeechToTextPlugin", "Buffer received: ${buffer.size} bytes")
        } else {
            Log.w("SpeechToTextPlugin", "Received null or empty buffer") // 버퍼가 null이거나 빈 경우 로그 출력
        }
    }

    private fun saveBufferToFile() {
        val context = pluginContext ?: return

        // 권한 확인
        if (!checkStoragePermission()) {
            requestStoragePermission() // 권한이 없다면 권한 요청
            Log.d(logTag, "Storage permission not granted. Requesting permission.")
            return // 권한이 없으면 파일 저장 중단
        }

        // 권한이 있다면 음성 파일 저장 진행
        val tempFile = File(context.cacheDir, "recorded_audio_${System.currentTimeMillis()}.pcm")
        recordedFilePath = tempFile.absolutePath
        Log.d(logTag, "Saving audio file to: $recordedFilePath")

        try {
            FileOutputStream(tempFile).use { fos: FileOutputStream ->
                for (buffer in bufferList) {
                    fos.write(buffer)
                }
            }

            bufferList.clear()

            // Flutter로 파일 경로 전달
            handler.post {
                debugLog("Audio file saved successfully at: $recordedFilePath")
                channel?.invokeMethod("onVoiceFileSaved", recordedFilePath)
            }
        } catch (e: IOException) {
            Log.e(logTag, "Failed to save audio buffer: ${e.message}")
        }
    }



    override fun onEvent(p0: Int, p1: Bundle?) {}
    override fun onBeginningOfSpeech() {
        Log.d("SpeechToTextPlugin", "onBeginningOfSpeech called")
    }
}

// See https://stackoverflow.com/questions/10538791/how-to-set-the-language-in-speech-recognition-on-android/10548680#10548680
class LanguageDetailsChecker(flutterResult: Result, logging: Boolean ) : BroadcastReceiver() {
    private val logTag = "SpeechToTextPlugin"
    private val result: Result = flutterResult
    private val debugLogging: Boolean = logging
    private var supportedLanguages: List<String>? = null

    private var languagePreference: String? = null

    override fun onReceive(context: Context, intent: Intent) {
        debugLog( "Received extra language broadcast" )
        val results = getResultExtras(true)
        if (results.containsKey(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE)) {
            languagePreference = results.getString(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE)
        }
        if (results.containsKey(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES)) {
            debugLog( "Extra supported languages" )
            supportedLanguages = results.getStringArrayList(
                RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES)
            createResponse(supportedLanguages)
        }
        else {
            debugLog(  "No extra supported languages" )
            createResponse( ArrayList<String>())
        }
    }

    public fun createResponse(supportedLanguages: List<String>?) {
        val currentLocale = Locale.getDefault()
        val localeNames = ArrayList<String>()
        localeNames.add(buildIdNameForLocale(currentLocale))
        if (null != supportedLanguages) {
            for (lang in supportedLanguages) {
                if (currentLocale.toLanguageTag() == lang) {
                    continue
                }
                val locale = Locale.forLanguageTag(lang)
                localeNames.add(buildIdNameForLocale(locale))
            }
        }
        result.success(localeNames)

    }

    private fun buildIdNameForLocale(locale: Locale): String {
        val name = locale.displayName.replace(':', ' ')
        return "${locale.language}_${locale.country}:$name"
    }

    private fun debugLog( msg: String ) {
        if ( debugLogging ) {
            Log.d( logTag, msg )
        }
    }
}

private class ChannelResultWrapper(result: Result) : Result {
    // Caller handler
    val handler: Handler = Handler(Looper.getMainLooper())
    val result: Result = result

    // make sure to respond in the caller thread
    override fun success(results: Any?) {

        handler.post {
            run {
                result.success(results);
            }
        }
    }

    override fun error(errorCode: String, errorMessage: String?, data: Any?) {
        handler.post {
            run {
                result.error(errorCode, errorMessage, data);
            }
        }
    }

    override fun notImplemented() {
        handler.post {
            run {
                result.notImplemented();
            }
        }
    }
}