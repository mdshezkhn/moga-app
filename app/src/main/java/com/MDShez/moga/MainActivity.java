package com.mdshez.moga;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String err = null;
        try {
            web = new WebView(this);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            // grant the page's mic requests (needed for WebView audio capture)
            web.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onPermissionRequest(final android.webkit.PermissionRequest request) {
                    runOnUiThread(() -> request.grant(request.getResources()));
                }
            });
            setContentView(web);
            try {
                new MoGaServer(this).start();          // port 8080
            } catch (Exception bind) {
                try {                                   // fallback: 8081
                    new MoGaServer(this, 8081).start();
                } catch (Exception e2) {
                    err = "server: " + bind + " / " + e2;
                }
            }
            web.addJavascriptInterface(new MoGaSpeech(this, web), "MoGaSpeech");
        } catch (Throwable t) {
            err = android.util.Log.getStackTraceString(t);
        }

        if (err != null) {                              // show the crash on screen
            TextView tv = new TextView(this);
            tv.setText("MoGa failed to start — screenshot this and send it:\n\n" + err);
            tv.setPadding(40, 60, 40, 40);
            ScrollView sv = new ScrollView(this);
            sv.addView(tv);
            setContentView(sv);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        web.loadUrl("http://127.0.0.1:8080/");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (web != null) web.destroy();
    }
}
