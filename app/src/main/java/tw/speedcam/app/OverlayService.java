package tw.speedcam.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;

/**
 * 浮動測速提示：在 Google 地圖（或任何 App）上面顯示時速、限速與前方測速點，並用語音提醒。
 * 資料來自 assets/cams.csv（警政署固定測速點）。
 */
public class OverlayService extends Service implements LocationListener {
    static final String CH = "speedcam_nav";
    static final String ACT_STOP = "tw.speedcam.app.STOP";
    static volatile boolean running = false;

    private WindowManager wm;
    private LinearLayout box;
    private TextView tvLimit, tvSpeed, tvAlert;
    private WindowManager.LayoutParams lp;
    private LocationManager lm;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    private double[] cLon = new double[2500], cLat = new double[2500];
    private int[] cLim = new int[2500], cType = new int[2500];
    private int n = 0;

    private int curCam = -1, curStage = 0;
    private long lastOver = 0;
    private static final int[] TH = {1000, 500, 150};

    /* 測試模式：不用 GPS，假裝朝一個真的測速點開過去 */
    static final String EXTRA_SIM = "sim";
    private boolean sim = false;
    private Handler simH;
    private double sLat, sLon, tLat;
    private int sLim = 50, sStep = 0;
    private final Runnable simTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            sStep++;
            float[] r = new float[1];
            Location.distanceBetween(sLat, sLon, tLat, sLon, r);
            double left = tLat - sLat;
            float kmh = left > 0 && r[0] < 650 ? sLim + 14 : (left > 0 && r[0] < 900 ? sLim + 5 : sLim - 10);
            if (left < 0 && r[0] > 120) { sLat = tLat - 1300 / 111320.0; curCam = -1; curStage = 0; }
            sLat += (kmh / 3.6) / 111320.0;
            Location loc = new Location("sim");
            loc.setLatitude(sLat); loc.setLongitude(sLon);
            loc.setSpeed(kmh / 3.6f); loc.setBearing(0f);
            loc.setTime(System.currentTimeMillis());
            onLocationChanged(loc);
            simH.postDelayed(this, 1000);
        }
    };

    @Override
    public IBinder onBind(Intent i) { return null; }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACT_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_STICKY;
        sim = intent != null && intent.getBooleanExtra(EXTRA_SIM, false);
        try {
            startFg();
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        loadCams();
        tts = new TextToSpeech(this, st -> {
            if (st == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(Locale.TAIWAN);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.CHINESE);
                ttsReady = true;
            }
        });
        try { buildOverlay(); } catch (Exception e) { /* 沒有浮動視窗權限時，仍保留語音提醒 */ }
        if (sim) {
            int k = -1;
            for (int i = 0; i < n; i++) if (cType[i] == 0 && cLim[i] >= 40 && cLim[i] <= 70) { k = i; break; }
            if (k < 0) k = 0;
            tLat = cLat[k]; sLon = cLon[k]; sLim = cLim[k] > 0 ? cLim[k] : 50;
            sLat = tLat - 1300 / 111320.0;
            simH = new Handler(Looper.getMainLooper());
            simH.postDelayed(() -> say("浮動視窗測試開始，正在模擬前往測速照相"), 1500);
            simH.postDelayed(simTick, 2500);
            return START_NOT_STICKY;
        }
        try {
            lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
        } catch (Exception e) {
            stopSelf();
        }
        return START_STICKY;
    }

    private void startFg() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH, "測速提醒", NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent po = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, OverlayService.class);
        stop.setAction(ACT_STOP);
        PendingIntent ps = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setContentTitle("測速提醒執行中").setContentText("測速提示會顯示在畫面上方，點這裡回到 App")
                .setSmallIcon(R.drawable.ic_launcher).setContentIntent(po).setOngoing(true)
                .addAction(0, "結束", ps);
        Notification nt = b.build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, nt, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        else startForeground(1, nt);
    }

    private void loadCams() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(getAssets().open("cams.csv"), "UTF-8"))) {
            String s;
            while ((s = br.readLine()) != null && n < cLon.length) {
                String[] p = s.split(",");
                if (p.length < 4) continue;
                cType[n] = Integer.parseInt(p[0].trim());
                cLon[n] = Double.parseDouble(p[1]);
                cLat[n] = Double.parseDouble(p[2]);
                cLim[n] = Integer.parseInt(p[3].trim());
                n++;
            }
        } catch (Exception ignored) { }
    }

    private void buildOverlay() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2081224);
        bg.setCornerRadius(dp(16));
        box.setBackground(bg);
        box.setPadding(dp(10), dp(8), dp(10), dp(8));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        tvLimit = new TextView(this);
        tvLimit.setGravity(Gravity.CENTER);
        tvLimit.setText("--");
        tvLimit.setTextSize(18);
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
        tvSpeed.setTextSize(30);
        tvSpeed.setTextColor(0xFFEAF1FB);
        tvSpeed.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sl.leftMargin = dp(10);
        row.addView(tvSpeed, sl);

        TextView unit = new TextView(this);
        unit.setText(" km/h");
        unit.setTextSize(11);
        unit.setTextColor(0xFF8FA3BF);
        row.addView(unit);

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(16);
        close.setTextColor(0xFF8FA3BF);
        close.setPadding(dp(14), dp(4), dp(2), dp(4));
        close.setOnClickListener(v -> stopSelf());
        row.addView(close);
        box.addView(row);

        tvAlert = new TextView(this);
        tvAlert.setTextSize(14);
        tvAlert.setTypeface(null, android.graphics.Typeface.BOLD);
        tvAlert.setTextColor(0xFF1D1300);
        tvAlert.setPadding(dp(10), dp(6), dp(10), dp(6));
        tvAlert.setVisibility(View.GONE);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        al.topMargin = dp(6);
        box.addView(tvAlert, al);

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
                    default: return false;
                }
            }
        });
        wm.addView(box, lp);
    }

    private void say(String t) {
        if (ttsReady && tts != null) tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "sc" + System.nanoTime());
    }

    private static String typeName(int t) {
        return t == 1 ? "區間測速" : t == 2 ? "闖紅燈照相" : "固定測速";
    }

    private static String spokenDist(int th) {
        return th == 1000 ? "一公里" : th == 500 ? "五百公尺" : "一百五十公尺";
    }

    private static String distLabel(float d) {
        if (d >= 1000) return String.format(Locale.US, "%.1f 公里", d / 1000f);
        return ((int) (d / 10)) * 10 + " 公尺";
    }

    @Override
    public void onLocationChanged(Location loc) {
        float sp = loc.hasSpeed() ? loc.getSpeed() * 3.6f : 0f;
        if (tvSpeed != null) tvSpeed.setText(String.valueOf(Math.round(sp)));
        boolean moving = loc.hasBearing() && loc.getSpeed() > 1.5f;
        int best = -1;
        float bd = 1e9f;
        if (moving) {
            float[] res = new float[2];
            double la = loc.getLatitude(), lo = loc.getLongitude();
            for (int i = 0; i < n; i++) {
                if (Math.abs(cLat[i] - la) > 0.015 || Math.abs(cLon[i] - lo) > 0.018) continue;
                Location.distanceBetween(la, lo, cLat[i], cLon[i], res);
                if (res[0] > 1200 || res[0] >= bd) continue;
                float diff = Math.abs(((res[1] - loc.getBearing() + 540f) % 360f) - 180f);
                if (diff > 50f) continue;
                best = i;
                bd = res[0];
            }
        }
        if (best < 0) {
            curCam = -1;
            curStage = 0;
            if (tvAlert != null) tvAlert.setVisibility(View.GONE);
            if (tvLimit != null) tvLimit.setText("--");
            return;
        }
        if (best != curCam) { curCam = best; curStage = 0; }
        int lim = cType[best] == 2 ? 0 : cLim[best];
        if (tvLimit != null) tvLimit.setText(lim > 0 ? String.valueOf(lim) : "--");
        boolean over = lim > 0 && sp > lim + 0.5f;
        String name = typeName(cType[best]);
        if (tvAlert != null) {
            String t = "前方 " + distLabel(bd) + "　" + name + (lim > 0 ? "　限速 " + lim : "");
            if (over) t = "超速！限速 " + lim + "　" + distLabel(bd);
            GradientDrawable ab = new GradientDrawable();
            ab.setCornerRadius(dp(10));
            ab.setColor(over ? 0xFFF0413C : 0xFFFFA814);
            tvAlert.setTextColor(over ? 0xFFFFFFFF : 0xFF1D1300);
            tvAlert.setBackground(ab);
            tvAlert.setText(t);
            tvAlert.setVisibility(View.VISIBLE);
        }
        int hit = -1;
        for (int i = 0; i < TH.length; i++) if (bd <= TH[i] && curStage <= i) hit = i;
        if (hit >= 0) {
            curStage = hit + 1;
            say("前方" + spokenDist(TH[hit]) + " " + name + (lim > 0 ? " 限速" + lim : ""));
        } else if (over && bd <= 500 && System.currentTimeMillis() - lastOver > 5000) {
            lastOver = System.currentTimeMillis();
            say("您已超速 限速" + lim);
        }
    }

    @Override public void onStatusChanged(String p, int s, Bundle e) { }
    @Override public void onProviderEnabled(String p) { }
    @Override public void onProviderDisabled(String p) { }

    @Override
    public void onDestroy() {
        running = false;
        try { if (simH != null) simH.removeCallbacksAndMessages(null); } catch (Exception ignored) { }
        try { if (lm != null) lm.removeUpdates(this); } catch (Exception ignored) { }
        try { if (wm != null && box != null) wm.removeView(box); } catch (Exception ignored) { }
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
