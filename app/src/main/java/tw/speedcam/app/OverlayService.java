package tw.speedcam.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.Set;

/**
 * 背景測速提醒。
 * - App 在前面時：待命（不定位、不講話，主畫面自己會提醒）。
 * - 離開 App 3 秒後：開始定位＋語音提醒；有「顯示在其他應用程式上層」權限就浮出小視窗，沒有也照樣用語音提醒。
 * 服務一定是在 App 還在前面時就啟動，所以 Android 12 以上背景也能繼續定位。
 */
public class OverlayService extends Service implements LocationListener, AlertEngine.Out {
    static final String CH = "speedcam_nav";
    static final String ACT_STOP = "tw.speedcam.app.STOP";
    static final String ACT_BG = "tw.speedcam.app.BG";
    static volatile boolean running = false;
    static volatile OverlayService inst = null;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final AlertEngine eng = new AlertEngine();
    private WindowManager wm;
    private LinearLayout box;
    private TextView tvLimit, tvSpeed, tvAlert;
    private WindowManager.LayoutParams lp;
    private LocationManager lm;
    private boolean locOn = false, bg = false, flash = false;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private android.media.ToneGenerator tone;
    private String vName = "";
    private float vRate = 1f;
    private boolean duck = true;

    @Override
    public IBinder onBind(Intent i) { return null; }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private SharedPreferences prefs() { return getSharedPreferences("p", MODE_PRIVATE); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent != null ? intent.getAction() : null;
        if (ACT_STOP.equals(act)) {
            prefs().edit().putBoolean("drv", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            try { startFg(); } catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
            running = true;
            inst = this;
            loadPts();
            loadCfg();
            tts = new TextToSpeech(this, st -> {
                if (st == TextToSpeech.SUCCESS) {
                    int r = tts.setLanguage(Locale.TAIWAN);
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.CHINESE);
                    ttsReady = true;
                }
            });
        }
        if (ACT_BG.equals(act)) goBg();
        else if (!bg) prepOverlay();
        return START_NOT_STICKY;
    }

    private void startFg() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH, "測速提醒", NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent po = PendingIntent.getActivity(this, 0, open, (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Intent stop = new Intent(this, OverlayService.class);
        stop.setAction(ACT_STOP);
        PendingIntent ps = PendingIntent.getService(this, 1, stop, (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setContentTitle("測速提醒執行中").setContentText("離開 App 也會繼續提醒，點這裡回到 App")
                .setSmallIcon(R.drawable.ic_stat).setContentIntent(po).setOngoing(true)
                .addAction(0, "結束", ps);
        Notification nt = b.build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, nt, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        else startForeground(1, nt);
    }

    /* ---------- 資料與設定（App 會同步過來） ---------- */

    void loadPts() {
        eng.clear();
        File f = new File(getFilesDir(), "pts.csv");
        if (f.exists() && f.length() > 1000) {
            try (InputStream in = new FileInputStream(f)) { readPts(in); } catch (Exception ignored) { }
        }
        if (eng.size() < 500) {
            eng.clear();
            readAsset("cams.csv");
            readAsset("tech.csv");
        }
    }

    /** pts.csv：種類,型態,經度,緯度,限速,名稱 */
    private void readPts(InputStream in) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String s;
        while ((s = br.readLine()) != null) {
            String[] p = s.split(",", 6);
            if (p.length < 5 || p[0].isEmpty()) continue;
            try {
                eng.add(p[0].charAt(0), Integer.parseInt(p[1].trim()), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                        Integer.parseInt(p[4].trim()), p.length > 5 ? p[5] : "");
            } catch (Exception ignored) { }
        }
    }

    /** 內建資料：型態,經度,緯度,限速（型態 0 固定 1 區間 2 闖紅燈 3 科技執法 4 跨線） */
    private void readAsset(String file) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(getAssets().open(file), "UTF-8"))) {
            String s;
            while ((s = br.readLine()) != null) {
                String[] p = s.split(",");
                if (p.length < 4) continue;
                try {
                    int t = Integer.parseInt(p[0].trim());
                    char k = t == 3 ? AlertEngine.TECH : t == 4 ? AlertEngine.CROSS : AlertEngine.CAM;
                    eng.add(k, t, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Integer.parseInt(p[3].trim()), "");
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }

    void loadCfg() {
        try {
            JSONObject j = new JSONObject(prefs().getString("cfg", "{}"));
            JSONObject en = j.optJSONObject("en");
            if (en != null) {
                eng.enC0 = en.optInt("c0", 1) != 0; eng.enC1 = en.optInt("c1", 1) != 0; eng.enC2 = en.optInt("c2", 1) != 0;
                eng.enT = en.optInt("t", 1) != 0; eng.enX = en.optInt("x", 1) != 0; eng.enU = en.optInt("u", 1) != 0;
            }
            eng.enH = j.optBoolean("hot", true);
            eng.grace = j.optInt("grace", 3);
            eng.cal = j.optInt("cal", 0);
            eng.iv = Math.max(10, j.optInt("iv", 10));
            eng.autoDist = !"std".equals(j.optString("dm", "auto"));
            eng.voice = j.optBoolean("voice", true);
            boolean male = "m".equals(j.optString("vg", "f"));
            vName = male ? j.optString("vnm", "") : j.optString("vnf", "");
            vRate = (float) j.optDouble("rate", 1);
            duck = j.optBoolean("duck", true);
        } catch (Exception ignored) { }
    }

    /* ---------- 前景／背景切換 ---------- */

    private boolean wantFloat() {
        // 縮小時自動浮動：內建一定開（有權限就浮出）
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    /** 離開 App：開始定位與提醒。回傳 true 代表背景提醒已經接手 */
    boolean goBg() {
        bg = true;
        android.util.Log.i("SpeedCamSay", "BG-START float=" + wantFloat() + " pts=" + eng.size());
        loadCfg();
        eng.resetTrip();
        if (wantFloat() && box == null) {
            try { buildOverlay(); } catch (Exception e) { box = null; }
        }
        showOverlay(true);
        startLoc();
        return true;
    }

    /** 回到 App：收起浮動視窗、停止背景提醒（主畫面接手） */
    void goFg() {
        android.util.Log.i("SpeedCamSay", "BG-STOP");
        bg = false;
        stopLoc();
        prepOverlay();
        try { if (tts != null) tts.stop(); } catch (Exception ignored) { }
    }

    /**
     * App 還在前面時就先把浮動視窗放好（隱藏、不擋觸控）。
     * 小米／紅米等手機不允許 App 在背景「新開」懸浮窗，先放好之後只是切換顯示，就不會被擋。
     */
    void prepOverlay() {
        if (!wantFloat()) { removeOverlay(); return; }
        if (box == null) { try { buildOverlay(); } catch (Exception e) { box = null; return; } }
        showOverlay(false);
    }

    private void showOverlay(boolean on) {
        if (box == null || lp == null || wm == null) return;
        try {
            // 隱藏時只把內容設成 GONE（視窗變 0 大小，不會擋到觸控）。
            // 不能用 FLAG_NOT_TOUCHABLE：Android 12 以上會因此把整個浮動視窗變半透明。
            box.setVisibility(on ? View.VISIBLE : View.GONE);
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            lp.alpha = 1f;
            wm.updateViewLayout(box, lp);
        } catch (Exception ignored) { }
    }

    private void startLoc() {
        if (locOn) return;
        try {
            lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
            locOn = true;
        } catch (Exception ignored) { }
    }

    private void stopLoc() {
        try { if (lm != null) lm.removeUpdates(this); } catch (Exception ignored) { }
        locOn = false;
    }

    /* ---------- AlertEngine 輸出 ---------- */

    /* 語音放到背景執行緒：不會卡住定位更新和浮動視窗 */
    private final java.util.concurrent.ExecutorService ttsQ = java.util.concurrent.Executors.newSingleThreadExecutor();
    private String curVoice = null;
    @Override
    public void say(String t) {
        android.util.Log.i("SpeedCamSay", "BG " + t);
        if (!ttsReady || tts == null) return;
        final String name = vName; final float rate = vRate;
        ttsQ.execute(() -> {
            try {
                tts.setSpeechRate(Math.max(0.5f, Math.min(2f, rate)));
                String key = name == null ? "" : name;
                if (!key.equals(curVoice)) {
                    Voice chosen = null;
                    if (!key.isEmpty()) {
                        Set<Voice> vs = tts.getVoices();
                        if (vs != null) for (Voice v : vs) if (v.getName().equals(key)) { chosen = v; break; }
                    }
                    if (chosen != null) tts.setVoice(chosen);
                    curVoice = key;
                }
                duckFor(t);
                tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "sc" + System.nanoTime());
            } catch (Exception ignored) { }
        });
    }

    private android.media.AudioFocusRequest afr;
    private void duckFor(String text) {
        if (!duck) return;
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                if (afr == null) afr = new android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).build();
                am.requestAudioFocus(afr);
            } else am.requestAudioFocus(null, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            h.removeCallbacks(unduck);
            h.postDelayed(unduck, 900 + text.length() * 230L);
        } catch (Exception ignored) { }
    }
    private final Runnable unduck = () -> {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) { if (afr != null) am.abandonAudioFocusRequest(afr); } else am.abandonAudioFocus(null);
        } catch (Exception ignored) { }
    };

    @Override
    public void beep(int lv) {
        try {
            if (tone == null) tone = new android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 90);
            tone.startTone(lv >= 3 ? android.media.ToneGenerator.TONE_CDMA_HIGH_L : android.media.ToneGenerator.TONE_PROP_BEEP2, lv >= 3 ? 450 : 250);
        } catch (Exception ignored) { }
    }

    @Override
    public void buzz(int lv) {
        long[] p = lv >= 3 ? new long[]{0, 500, 120, 500, 120, 500} : new long[]{0, 300, 150, 300};
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createWaveform(p, -1));
            else v.vibrate(p, -1);
        } catch (Exception ignored) { }
    }

    /* ---------- 定位 ---------- */

    /* 背景時也把行車路線記下來，回到 App 時交給畫面併進「路線紀錄」（不然路線會變一條直線） */
    static final StringBuilder TRACK = new StringBuilder();
    private static double tLa = 0, tLo = 0; private static long tT = 0;
    static synchronized String takeTrack() { String r = TRACK.toString(); TRACK.setLength(0); return r; }
    private static synchronized void addTrack(double la, double lo, float kmh, long now) {
        if (tT != 0 && AlertEngine.dist(tLa, tLo, la, lo) < 10 && now - tT < 30000) return;
        if (TRACK.length() > 1500000) return;   // 大約 4 萬個點，夠開一整天
        TRACK.append(String.format(Locale.US, "%.5f,%.5f,%d,%d;", la, lo, Math.round(kmh), now));
        tLa = la; tLo = lo; tT = now;
    }

    @Override
    public void onLocationChanged(Location loc) {
        if (!bg) return;
        addTrack(loc.getLatitude(), loc.getLongitude(), loc.hasSpeed() ? loc.getSpeed() * 3.6f : eng.speed, System.currentTimeMillis());
        Float sp = loc.hasSpeed() ? loc.getSpeed() : null;
        Float br = loc.hasBearing() && loc.hasSpeed() && loc.getSpeed() > 1.5f ? loc.getBearing() : null;
        eng.update(loc.getLatitude(), loc.getLongitude(), sp, br, System.currentTimeMillis(), this);
        paint();
    }

    private static String distLabel(float d) {
        if (d >= 1000) return String.format(Locale.US, "%.1f 公里", d / 1000f);
        return ((int) (d / 10)) * 10 + " 公尺";
    }

    private void paint() {
        if (box == null) return;
        tvSpeed.setText(String.valueOf(Math.round(eng.speed)));
        tvLimit.setText(eng.curLim > 0 ? String.valueOf(eng.curLim) : "--");
        int i = eng.nearIdx;
        if (i < 0) { tvAlert.setVisibility(View.GONE); setBorder(0); return; }
        flash = !flash;
        int lv = eng.level;
        String nm = AlertEngine.typeName(eng.kind[i], eng.type[i]);
        if (eng.kind[i] == AlertEngine.MINE && !eng.name[i].isEmpty()) nm = nm + " " + eng.name[i];
        String t = "前方 " + distLabel(eng.nearD) + "　" + nm + (eng.kind[i] == AlertEngine.CAM && eng.lim[i] > 0 ? "　限速 " + eng.lim[i] : "");
        if (eng.isOver) t = "超速！限速 " + eng.curLim + "　" + distLabel(eng.camD);
        if (lv >= 3) t = "立刻減速！超速 " + Math.round(eng.speed - eng.curLim) + "　" + distLabel(eng.camD);
        boolean wht = lv >= 2 && flash;
        GradientDrawable ab = new GradientDrawable();
        ab.setCornerRadius(dp(10));
        int col = 0xFFFFA814;
        char k = eng.kind[i];
        if (k == AlertEngine.TECH) col = 0xFF00B4D8; else if (k == AlertEngine.CROSS) col = 0xFFFF4FA0;
        else if (k == AlertEngine.HOT) col = 0xFF8B5CF6; else if (k == AlertEngine.MINE) col = 0xFF16B26B;
        ab.setColor(eng.isOver ? (wht ? 0xFFFFFFFF : 0xFFF0413C) : col);
        tvAlert.setTextColor(eng.isOver ? (wht ? 0xFFC4120C : 0xFFFFFFFF) : (k == AlertEngine.CAM ? 0xFF1D1300 : 0xFFFFFFFF));
        tvAlert.setBackground(ab);
        tvAlert.setText(t);
        tvAlert.setVisibility(View.VISIBLE);
        setBorder(lv);
    }

    private void setBorder(int lv) {
        GradientDrawable bgd = new GradientDrawable();
        bgd.setCornerRadius(dp(16));
        bgd.setColor(0xF2081224);
        if (lv >= 2) bgd.setStroke(dp(4), flash ? 0xFFF0413C : 0xFFFFFFFF);
        box.setBackground(bgd);
    }

    @Override public void onStatusChanged(String p, int s, Bundle e) { }
    @Override public void onProviderEnabled(String p) { }
    @Override public void onProviderDisabled(String p) { }

    /* ---------- 浮動視窗 ---------- */

    private void buildOverlay() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setMinimumWidth(dp(280));
        box.setPadding(dp(10), dp(8), dp(10), dp(8));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        tvLimit = new TextView(this);
        tvLimit.setGravity(Gravity.CENTER);
        tvLimit.setText("--");
        tvLimit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 18);
        tvLimit.setTextColor(0xFF111111);
        tvLimit.setTypeface(null, android.graphics.Typeface.BOLD);
        GradientDrawable ov = new GradientDrawable();
        ov.setShape(GradientDrawable.OVAL);
        ov.setColor(0xFFFFFFFF);
        ov.setStroke(dp(5), 0xFFF0413C);
        tvLimit.setBackground(ov);
        row.addView(tvLimit, new LinearLayout.LayoutParams(dp(48), dp(48)));

        tvSpeed = new TextView(this);
        tvSpeed.setText("0");
        tvSpeed.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 30);
        tvSpeed.setTextColor(0xFFEAF1FB);
        tvSpeed.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sl.leftMargin = dp(10);
        row.addView(tvSpeed, sl);

        TextView unit = new TextView(this);
        unit.setText(" km/h");
        unit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11);
        unit.setTextColor(0xFF8FA3BF);
        row.addView(unit);

        TextView open = new TextView(this);
        open.setText("回主畫面");
        open.setSingleLine(true);
        open.setMinWidth(dp(76));
        open.setGravity(Gravity.CENTER);
        open.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
        open.setTypeface(null, android.graphics.Typeface.BOLD);
        open.setTextColor(0xFF04251D);
        GradientDrawable ob = new GradientDrawable();
        ob.setCornerRadius(dp(10));
        ob.setColor(0xFF47E8C0);
        open.setBackground(ob);
        open.setPadding(dp(10), dp(5), dp(10), dp(5));
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(dp(84), LinearLayout.LayoutParams.WRAP_CONTENT);
        ol.leftMargin = dp(12);
        open.setOnClickListener(v -> openApp());
        row.addView(open, ol);

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16);
        close.setTextColor(0xFF8FA3BF);
        close.setPadding(dp(14), dp(4), dp(2), dp(4));
        close.setOnClickListener(v -> { prefs().edit().putBoolean("drv", false).apply(); stopSelf(); });
        row.addView(close);
        box.addView(row);

        tvAlert = new TextView(this);
        tvAlert.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        tvAlert.setTypeface(null, android.graphics.Typeface.BOLD);
        tvAlert.setPadding(dp(10), dp(6), dp(10), dp(6));
        tvAlert.setVisibility(View.GONE);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        al.topMargin = dp(6);
        box.addView(tvAlert, al);
        setBorder(0);

        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        lp.x = dp(8);
        lp.y = Math.max(dp(120), dm.heightPixels - dp(360));

        box.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy; int sx, sy;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN: dx = e.getRawX(); dy = e.getRawY(); sx = lp.x; sy = lp.y; return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = sx + (int) (e.getRawX() - dx);
                        lp.y = sy + (int) (e.getRawY() - dy);
                        try { wm.updateViewLayout(box, lp); } catch (Exception ignored) { }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (Math.abs(e.getRawX() - dx) < dp(8) && Math.abs(e.getRawY() - dy) < dp(8)) openApp();
                        return true;
                    default: return false;
                }
            }
        });
        wm.addView(box, lp);
    }

    private void removeOverlay() {
        try { if (wm != null && box != null) wm.removeView(box); } catch (Exception ignored) { }
        box = null;
    }

    /** 點浮動視窗（或「回主畫面」）：直接回到 App 主畫面 */
    private void openApp() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Exception ignored) { }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 使用者把 App 從最近使用清單滑掉：跟著結束
        prefs().edit().putBoolean("drv", false).apply();
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        inst = null;
        h.removeCallbacksAndMessages(null);
        stopLoc();
        removeOverlay();
        if (tts != null) { try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { } }
        try { if (tone != null) tone.release(); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
