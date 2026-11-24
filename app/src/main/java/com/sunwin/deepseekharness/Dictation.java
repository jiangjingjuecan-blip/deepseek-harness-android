package com.sunwin.deepseekharness;

import android.content.*;
import android.os.*;
import android.speech.*;
import java.util.ArrayList;

final class Dictation implements RecognitionListener {
    private final MainActivity ui;
    private SpeechRecognizer speech;
    Dictation(MainActivity ui) { this.ui=ui; }
    boolean available() { return Build.VERSION.SDK_INT>=31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ui); }
    void start() {
        stop();
        try {
            if (!available()) { ui.toast("本机语音识别不可用"); return; }
            speech=SpeechRecognizer.createOnDeviceSpeechRecognizer(ui); speech.setRecognitionListener(this);
            Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN")
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true);
            speech.startListening(intent); ui.voiceState(true);
        } catch (RuntimeException e) { stop(); ui.toast("本机语音识别不可用"); }
    }
    void stop() { if (speech!=null) { speech.destroy(); speech=null; } ui.voiceState(false); }
    @Override public void onResults(Bundle result) {
        ArrayList<String> text=result.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (text!=null && !text.isEmpty()) ui.transcribed(text.get(0));
        stop();
    }
    @Override public void onError(int code) { stop(); ui.toast("未能识别语音"); }
    @Override public void onReadyForSpeech(Bundle b) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float db) { }
    @Override public void onBufferReceived(byte[] b) { }
    @Override public void onEndOfSpeech() { ui.voiceState(false); }
    @Override public void onPartialResults(Bundle b) { }
    @Override public void onEvent(int e,Bundle b) { }
}
