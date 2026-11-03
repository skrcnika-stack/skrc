package tw.speedcam.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private GeolocationPermissions.Callback pendingCb;
    private String pendingOrigin;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.parseColor("#050B17"));
        getWindow().setNavigationBarColor(Color.parseColor("#050B17"));

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(Locale.TAIWAN);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.CHINESE);
                }
                ttsReady = true;
            }
        });

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#050B17"));
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "AndroidTTS");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                if (HOST.equals(req.getUrl().getHost())) {
                    try {
                        String path = req.getUrl().getPath();
                        if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
                        InputStream in = getAssets().open(path.substring(1));
                        String mime = path.endsWith(".html") ? "text/html" : "application/octet-stream";
                        return new WebResourceResponse(mime, "utf-8", in);
                    } catch (Exception e) { return null; }
                }
                return null;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    cb.invoke(origin, true, false);
                } else {
                    pendingCb = cb; pendingOrigin = origin;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, 7);
                }
            }
        });
        web.loadUrl("https://" + HOST + "/index.html");
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 7);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        if (pendingCb != null) { pendingCb.invoke(pendingOrigin, ok, false); pendingCb = null; }
    }

    /** Native speech bridge (the phone's own Google zh-TW voices) */
    public class Bridge {
        @JavascriptInterface
        public String voices() {
            JSONArray a = new JSONArray();
            try {
                Set<Voice> vs = tts.getVoices();
                if (vs != null) for (Voice v : vs) {
                    if (!"zh".equalsIgnoreCase(v.getLocale().getLanguage())) continue;
                    JSONObject o = new JSONObject();
                    o.put("n", v.getName());
                    o.put("l", v.getLocale().toLanguageTag());
                    o.put("net", v.isNetworkConnectionRequired());
                    a.put(o);
                }
            } catch (Exception ignored) {}
            return a.toString();
        }

        @JavascriptInterface
        public void speak(String text, float rate, float pitch, String gender, String voiceName) {
            runOnUiThread(() -> {
                if (!ttsReady) return;
                try {
                    tts.setSpeechRate(Math.max(0.5f, Math.min(2f, rate)));
                    tts.setPitch(Math.max(0.5f, Math.min(2f, pitch)));
                    Voice chosen = null;
                    Set<Voice> vs = tts.getVoices();
                    if (voiceName != null && !voiceName.isEmpty() && vs != null) {
                        for (Voice v : vs) if (v.getName().equals(voiceName)) { chosen = v; break; }
                    }
                    if (chosen == null && "m".equals(gender) && vs != null) {
                        // Male guess: zh-TW voice named ctd (prefer offline)
                        for (Voice v : vs) {
                            String n = v.getName().toLowerCase(Locale.ROOT);
                            if (n.contains("cmn-tw") && n.contains("-ctd-")) {
                                if (chosen == null || (chosen.isNetworkConnectionRequired() && !v.isNetworkConnectionRequired())) chosen = v;
                            }
                        }
                    }
                    if (chosen != null) tts.setVoice(chosen);
                    else tts.setLanguage(Locale.TAIWAN); // back to the phone's default zh-TW voice
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sc" + System.nanoTime());
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void stop() { runOnUiThread(() -> { try { tts.stop(); } catch (Exception ignored) {} }); }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
