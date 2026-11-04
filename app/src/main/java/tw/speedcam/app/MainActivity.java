package tw.speedcam.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
        web.addJavascriptInterface(new Tdx(), "AndroidTDX");
        web.addJavascriptInterface(new Nav(), "AndroidNav");

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

    private boolean autoFloat() {
        return getSharedPreferences("p", MODE_PRIVATE).getBoolean("auto", true);
    }

    /** App 離開畫面（按 Home、切 App、關螢幕、開 Google 地圖）3 秒後：自動改成浮動測速提示 */
    private final android.os.Handler floatH = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean inFront = false;
    private final Runnable floatRun = () -> {
        try {
            if (inFront || OverlayService.running) return;
            if (autoFloat() && (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this))
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                Intent s = new Intent(this, OverlayService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
            }
        } catch (Exception ignored) { }
    };

    @Override
    protected void onStop() {
        super.onStop();
        floatH.removeCallbacks(floatRun);
        if (!isFinishing() && !isChangingConfigurations()) floatH.postDelayed(floatRun, 3000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        inFront = false;
    }

    /** 回到主介面：收起浮動視窗，維持主介面 */
    @Override
    protected void onResume() {
        super.onResume();
        inFront = true;
        floatH.removeCallbacks(floatRun);
        try { stopService(new Intent(this, OverlayService.class)); } catch (Exception ignored) { }
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


    /** TDX (交通部) bridge: token + GET, only to tdx.transportdata.tw. Key stays on the phone. */
    public class Tdx {
        private String token = null, tokenFor = null;
        private long tokenExp = 0;

        private String read(HttpURLConnection c, boolean err) throws Exception {
            InputStream in = err ? c.getErrorStream() : c.getInputStream();
            if (in == null) return "";
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n; int tot = 0;
            while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); tot += n; if (tot > 6000000) break; }
            in.close();
            return o.toString("UTF-8");
        }

        @JavascriptInterface
        public void request(final String id, final String cid, final String sec, final String path) {
            new Thread(() -> {
                boolean ok = false; String out;
                try {
                    if (path == null || !path.startsWith("/api/")) throw new Exception("路徑不允許");
                    if (token == null || !cid.equals(tokenFor) || System.currentTimeMillis() > tokenExp) {
                        HttpURLConnection c = (HttpURLConnection) new URL("https://tdx.transportdata.tw/auth/realms/TDXConnect/protocol/openid-connect/token").openConnection();
                        c.setConnectTimeout(15000); c.setReadTimeout(20000);
                        c.setRequestMethod("POST"); c.setDoOutput(true);
                        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                        String body = "grant_type=client_credentials&client_id=" + java.net.URLEncoder.encode(cid, "UTF-8")
                                + "&client_secret=" + java.net.URLEncoder.encode(sec, "UTF-8");
                        OutputStream os = c.getOutputStream(); os.write(body.getBytes("UTF-8")); os.close();
                        int code = c.getResponseCode();
                        String t = read(c, code >= 400);
                        if (code >= 400) throw new Exception("金鑰驗證失敗 HTTP " + code + " " + (t.length() > 120 ? t.substring(0, 120) : t));
                        JSONObject jo = new JSONObject(t);
                        token = jo.getString("access_token"); tokenFor = cid;
                        tokenExp = System.currentTimeMillis() + Math.max(60, jo.optLong("expires_in", 3600) - 120) * 1000L;
                    }
                    HttpURLConnection g = (HttpURLConnection) new URL("https://tdx.transportdata.tw" + path).openConnection();
                    g.setConnectTimeout(15000); g.setReadTimeout(30000);
                    g.setRequestProperty("authorization", "Bearer " + token);
                    int code = g.getResponseCode();
                    String t = read(g, code >= 400);
                    if (code >= 400) throw new Exception("HTTP " + code + " " + (t.length() > 140 ? t.substring(0, 140) : t));
                    out = t; ok = true;
                } catch (Exception e) {
                    out = String.valueOf(e.getMessage() != null ? e.getMessage() : e);
                }
                final String o = out; final boolean k = ok;
                runOnUiThread(() -> web.evaluateJavascript("window.__tdxCb(" + JSONObject.quote(id) + "," + k + "," + JSONObject.quote(o) + ")", null));
            }).start();
        }
    }

    /** 開 Google 地圖導航，同時啟動浮動測速提示 */
    public class Nav {
        @JavascriptInterface
        public boolean overlayOk() {
            return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(MainActivity.this);
        }

        @JavascriptInterface
        public boolean getAuto() { return autoFloat(); }

        @JavascriptInterface
        public void setAuto(boolean on) {
            getSharedPreferences("p", MODE_PRIVATE).edit().putBoolean("auto", on).apply();
        }

        @JavascriptInterface
        public void askOverlay() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public boolean running() { return OverlayService.running; }

        @JavascriptInterface
        public void vibrate(String pattern) {
            try {
                String[] p = pattern.split(",");
                long[] t = new long[p.length];
                for (int i = 0; i < p.length; i++) t[i] = Long.parseLong(p[i].trim());
                android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v == null) return;
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createWaveform(t, -1));
                else v.vibrate(t, -1);
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void setVeh(String v) {
            getSharedPreferences("p", MODE_PRIVATE).edit().putString("veh", v == null ? "car" : v).apply();
        }

        /** 浮動視窗測試：模擬開往測速點，並把 App 縮到背景 */
        @JavascriptInterface
        public String testOverlay() {
            if (!overlayOk()) {
                runOnUiThread(() -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) { }
                });
                return "need_overlay";
            }
            runOnUiThread(() -> {
                try {
                    try { stopService(new Intent(MainActivity.this, OverlayService.class)); } catch (Exception ignored) { }
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 8);
                    }
                    Intent s = new Intent(MainActivity.this, OverlayService.class);
                    s.putExtra(OverlayService.EXTRA_SIM, true);
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> moveTaskToBack(true), 400);
                } catch (Exception ignored) { }
            });
            return "ok";
        }

        @JavascriptInterface
        public void stop() {
            runOnUiThread(() -> {
                try { stopService(new Intent(MainActivity.this, OverlayService.class)); } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public String start(final String dest, final String mode) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, 7));
                return "need_location";
            }
            if (!overlayOk()) {
                runOnUiThread(() -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) { }
                });
                return "need_overlay";
            }
            runOnUiThread(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 8);
                    }
                    Intent s = new Intent(MainActivity.this, OverlayService.class);
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
                } catch (Exception ignored) { }
                String m = "l".equals(mode) ? "l" : "d";
                try {
                    Intent g = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest) + "&mode=" + m));
                    g.setPackage("com.google.android.apps.maps");
                    startActivity(g);
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination="
                                + Uri.encode(dest) + "&travelmode=" + ("l".equals(m) ? "two-wheeler" : "driving"))));
                    } catch (Exception ignored) { }
                }
            });
            return "ok";
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
