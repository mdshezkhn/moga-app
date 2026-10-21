package com.mdshez.moga;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

/** Native speech-to-text bridge: Android's SpeechRecognizer, exposed to the
 *  web UI. WebView lacks Chrome's SpeechRecognition service, so the mic
 *  button routes through this instead — results land in the page via
 *  window.moGaSpeechResult(text). */
public class MoGaSpeech {
    private final Activity act;
    private final WebView web;
    private SpeechRecognizer sr;

    public MoGaSpeech(Activity a, WebView w) {
        act = a;
        web = w;
    }

    @JavascriptInterface
    public void start() {
        act.runOnUiThread(() -> {
            if (!SpeechRecognizer.isRecognitionAvailable(act)) {
                push("__error__:this phone has no speech recognition service");
                return;
            }
            if (sr == null) sr = SpeechRecognizer.createSpeechRecognizer(act);
            sr.setRecognitionListener(new RecognitionListener() {
                @Override public void onResults(Bundle r) {
                    java.util.ArrayList<String> list =
                            r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    push(list != null && !list.isEmpty() ? list.get(0) : "__error__:nothing heard");
                }
                @Override public void onError(int e) {
                    push("__error__:" + (e == SpeechRecognizer.ERROR_NO_MATCH ? "nothing heard"
                            : e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                            ? "microphone permission missing" : "mic error code " + e));
                }
                @Override public void onReadyForSpeech(Bundle p) { push("__listening__"); }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rms) {}
                @Override public void onBufferReceived(byte[] b) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onPartialResults(Bundle p) {}
                @Override public void onEvent(int t, Bundle p) {}
            });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            sr.startListening(i);
        });
    }

    @JavascriptInterface
    public void stop() {
        if (sr != null) act.runOnUiThread(() -> sr.stopListening());
    }

    private void push(final String text) {
        act.runOnUiThread(() -> {
            String js = "window.moGaSpeechResult && window.moGaSpeechResult("
                    + org.json.JSONObject.quote(text) + ");";
            web.evaluateJavascript(js, null);
        });
    }
}
