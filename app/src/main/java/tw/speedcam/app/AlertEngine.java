package tw.speedcam.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 提醒判斷的核心（不用 Android 類別，可以單獨測試）。
 * 規則跟 App 主畫面一致：
 *  - 只看行進方向前方 ±55 度
 *  - 對向不報：測速點有標「被拍的車流方向」時，跟自己的行進方向差超過 125 度就不報（方向不明照報）
 *  - 測速：1 公里 / 500 / 150 公尺（時速 90 以上自動改 1.5 公里 / 800 / 300）
 *  - 科技執法、跨線、自建點：500 / 150 公尺
 *  - 取締熱點：300 公尺
 *  - 超速：200 公尺外每 10 秒提醒；200 公尺內每 3 秒＋警示音＋震動；100 公尺內或還在加速每 2 秒
 */
public class AlertEngine {
    public interface Out {
        void say(String text);
        void beep(int lv);
        void buzz(int lv);
    }

    public static final char CAM = 'c', TECH = 't', CROSS = 'x', HOT = 'h', MINE = 'u';

    int n = 0;
    double[] lon = new double[8000], lat = new double[8000];
    int[] lim = new int[8000], type = new int[8000];
    /** 被拍的車流行進方向（0–359 度）；-1＝雙向或不明 */
    int[] dir = new int[8000];
    char[] kind = new char[8000];
    String[] name = new String[8000];

    /* 設定（來自 App 設定頁） */
    public boolean enC0 = true, enC1 = true, enC2 = true, enT = true, enX = true, enH = true, enU = true;
    public boolean autoDist = true, voice = true, opp = true;
    public int grace = 3, cal = 0, iv = 10;

    /* 狀態 */
    private final HashMap<Integer, int[]> stage = new HashMap<>();
    private final HashMap<Integer, Long> seenAt = new HashMap<>();
    private boolean over = false;
    private int lv = 0, nSay = 0;
    private long lastSay = 0, busyUntil = 0;
    private float sp200 = -1f;
    private double hd = -1;
    private boolean hasPrev = false;
    private double pLat, pLon;
    private long pT, lastT = 0;

    /* 給畫面用的結果 */
    public float speed = 0f;
    public int nearIdx = -1, camIdx = -1, curLim = 0, level = 0;
    public float nearD = 0f, camD = 0f;
    public boolean isOver = false, moving = false;
    public String lastText = "";

    /** 測試用：某個點目前講到第幾段 */
    int debugStage(int i) { int[] a = stage.get(i); return a == null ? 0 : a[0]; }

    public void clear() { n = 0; stage.clear(); seenAt.clear(); }

    public int size() { return n; }

    public void add(char k, int t, double lo, double la, int li, String nm) {
        add(k, t, lo, la, li, nm, -1);
    }

    public void add(char k, int t, double lo, double la, int li, String nm, int dr) {
        if (n >= lon.length) return;
        kind[n] = k; type[n] = t; lon[n] = lo; lat[n] = la; lim[n] = li; name[n] = nm == null ? "" : nm;
        dir[n] = (k == CAM && dr >= 0 && dr < 360) ? dr : -1;
        n++;
    }

    public void resetTrip() {
        stage.clear(); seenAt.clear(); over = false; lv = 0; nSay = 0; lastSay = 0; busyUntil = 0;
        sp200 = -1f; hd = -1; hasPrev = false; lastT = 0;
    }

    static final double R = 6371000, D2R = Math.PI / 180;

    public static double dist(double la1, double lo1, double la2, double lo2) {
        double x = (la2 - la1) * D2R, y = (lo2 - lo1) * D2R * Math.cos((la1 + la2) / 2 * D2R);
        return Math.sqrt(x * x + y * y) * R;
    }

    public static double bearing(double la1, double lo1, double la2, double lo2) {
        double y = Math.sin((lo2 - lo1) * D2R) * Math.cos(la2 * D2R);
        double x = Math.cos(la1 * D2R) * Math.sin(la2 * D2R) - Math.sin(la1 * D2R) * Math.cos(la2 * D2R) * Math.cos((lo2 - lo1) * D2R);
        return (Math.atan2(y, x) / D2R + 360) % 360;
    }

    static double adiff(double a, double b) {
        double d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    public static String typeName(char k, int t) {
        if (k == MINE) return "自建提醒點";
        if (k == HOT) return "移動測速熱點";
        if (k == TECH) return "科技執法";
        if (k == CROSS) return "跨線取締";
        return t == 1 ? "區間測速" : t == 2 ? "闖紅燈照相" : "固定測速";
    }

    private boolean enabled(int i) {
        switch (kind[i]) {
            case HOT: return enH;
            case TECH: return enT;
            case CROSS: return enX;
            case MINE: return enU;
            default: return type[i] == 1 ? enC1 : type[i] == 2 ? enC2 : enC0;
        }
    }

    private void speak(Out out, String t, long now) {
        lastText = t;
        busyUntil = now + 800 + t.length() * 230L;
        if (voice) out.say(t);
    }

    /**
     * 每收到一次定位就呼叫。
     * @param spdMs GPS 速度（公尺/秒），沒有就給 null
     * @param brg   GPS 行進方向（度），沒有就給 null
     */
    public void update(double la, double lo, Float spdMs, Float brg, long now, Out out) {
        Double sp = spdMs != null && !spdMs.isNaN() ? (double) (spdMs * 3.6f) : null;
        Double h = brg != null && !brg.isNaN() ? (double) brg : null;
        if (hasPrev) {
            double d = dist(pLat, pLon, la, lo), dt = (now - pT) / 1000.0;
            boolean glitch = false;
            if (sp == null && dt > 0) {
                sp = d / dt * 3.6;
                if (sp > 220 || dt > 5) { sp = (double) speed / (1 + cal / 100.0); glitch = true; } // GPS 跳點，不採用
            }
            if (d > 5) {
                if (h == null && !glitch) h = bearing(pLat, pLon, la, lo);
                pLat = la; pLon = lo; pT = now;
            }
        } else { hasPrev = true; pLat = la; pLon = lo; pT = now; }
        double spd = (sp == null ? 0 : sp) * (1 + cal / 100.0);
        // 車子不可能 1 秒加速超過 30 公里：GPS 突然跳很高就慢慢跟上
        if (lastT > 0) { double q = Math.max(0.5, (now - lastT) / 1000.0); if (spd > speed + 30 * q) spd = speed + 30 * Math.min(q, 1); }
        lastT = now;
        if (h != null && spd >= 3) hd = h;
        speed = (float) spd;
        moving = spd >= 8 && hd >= 0;

        /* 找前方的點 */
        ArrayList<double[]> cand = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (Math.abs(lat[i] - la) > 0.015 || Math.abs(lon[i] - lo) > 0.017) continue;
            if (!enabled(i)) continue;
            double d = dist(la, lo, lat[i], lon[i]);
            char k = kind[i];
            double maxd = k == HOT ? 400 : (k == TECH || k == CROSS || k == MINE) ? 600 : 1500;
            if (d > maxd) continue;
            if (moving && adiff(bearing(la, lo, lat[i], lon[i]), hd) > 55) continue;
            if (moving && opp && dir[i] >= 0 && adiff(dir[i], hd) > 125) continue; // 只拍對向車道：不報
            cand.add(new double[]{d, i});
        }
        Collections.sort(cand, (a, b) -> Double.compare(a[0], b[0]));

        boolean far = autoDist && spd >= 90;
        int[] T = far ? new int[]{1500, 800, 300} : new int[]{1000, 500, 150};
        String[] DW = far ? new String[]{"", "一公里半", "八百公尺", "三百公尺"} : new String[]{"", "一公里", "五百公尺", "一百五十公尺"};
        boolean spoke = false;
        for (double[] c : cand) {
            int i = (int) c[1];
            double d = c[0];
            seenAt.put(i, now);
            int[] a = stage.get(i);
            if (a == null) { a = new int[]{0}; stage.put(i, a); }
            char k = kind[i];
            int st;
            if (k == HOT) st = d <= 300 ? 3 : 0;
            else if (k == TECH || k == CROSS || k == MINE) st = d <= 150 ? 3 : d <= 500 ? 2 : 0;
            else st = d <= T[2] ? 3 : d <= T[1] ? 2 : d <= T[0] ? 1 : 0;
            if (!moving || st <= a[0]) continue;
            // 同一時間只講一句：正在講別的就等講完；最近的那個點講最後一段時例外，直接插播
            boolean urgent = st == 3 && (c == cand.get(0) || d <= 80);
            if (spoke || (!urgent && now < busyUntil)) continue;
            a[0] = st;
            String L = String.valueOf(lim[i]);
            String tx = null;
            if (k == MINE) tx = st == 2 ? "前方五百公尺，自建提醒點，" + name[i] + "。" : "自建提醒點到了，" + name[i] + "，請小心。";
            else if (k == TECH) tx = st == 2 ? "前方五百公尺，科技執法路口。" : "科技執法路口到了，請注意號誌與行人。";
            else if (k == CROSS) tx = st == 2 ? "前方五百公尺，跨線取締，請勿跨越雙白線。" : "跨線取締到了，請保持在車道內。";
            else if (k == HOT) tx = "前面三百公尺，這段常有取締，請留意車速。";
            else if (type[i] == 1) tx = st == 1 ? "前方" + DW[1] + "，要進入區間測速了。" : st == 2 ? "前方" + DW[2] + "，區間測速開始，限速" + L + "。" : "即將進入區間測速，請保持限速" + L + "。";
            else if (type[i] == 2) tx = st == 1 ? "前方" + DW[1] + "，有闖紅燈照相。" : st == 2 ? "前方" + DW[2] + "，闖紅燈照相，請注意號誌。" : "前方" + DW[3] + "，闖紅燈照相路口，請小心。";
            else tx = st == 1 ? "前方" + DW[1] + "，有測速照相。" : st == 2 ? "前方" + DW[2] + "，測速照相，限速" + L + "。" : "前方" + DW[3] + "，測速照相，限速" + L + "，請減速。";
            speak(out, tx, now);
            spoke = true;
            // 同一個位置（30 公尺內）的同類點算已經講過，不會講兩次
            for (double[] o : cand) {
                int j = (int) o[1];
                if (j == i || kind[j] != k || dist(lat[i], lon[i], lat[j], lon[j]) > 30) continue;
                int[] b = stage.get(j);
                if (b == null) { b = new int[]{0}; stage.put(j, b); seenAt.put(j, now); }
                if (b[0] < st) b[0] = st;
            }
        }
        for (Iterator<Map.Entry<Integer, Long>> it = seenAt.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Long> e = it.next();
            if (now - e.getValue() > 20000) { stage.remove(e.getKey()); it.remove(); }
        }

        /* 超速 */
        nearIdx = cand.isEmpty() ? -1 : (int) cand.get(0)[1];
        nearD = cand.isEmpty() ? 0 : (float) cand.get(0)[0];
        camIdx = -1;
        for (double[] c : cand) if (kind[(int) c[1]] == CAM) { camIdx = (int) c[1]; camD = (float) c[0]; break; }
        int L = (camIdx >= 0 && camD <= 600) ? lim[camIdx] : 0;
        curLim = L;
        boolean ov = L > 0 && moving && spd > L + grace;
        if (ov) {
            if (!over) { over = true; nSay = 0; lastSay = 0; lv = 0; sp200 = -1f; }
            if (camD > 200) sp200 = -1f; else if (sp200 < 0) sp200 = (float) spd;
            boolean accel = sp200 >= 0 && spd > sp200 + 3;
            int nl = camD > 200 ? 1 : (camD <= 100 || accel) ? 3 : 2;
            boolean jump = false;
            if (nl != lv) {
                jump = nl > lv && nl >= 2; // 升級：馬上警示，可以插播
                lv = nl;
                if (jump) lastSay = 0;
            }
            long ivl = lv == 1 ? Math.max(iv, 10) * 1000L : lv == 2 ? 3000 : 2000;
            if (now - lastSay >= ivl) {
                if (lv >= 2) { if (voice) out.beep(lv); out.buzz(lv); }
                if (jump || now >= busyUntil) {
                    nSay++;
                    int by = (int) Math.round(spd - L);
                    String m = lv == 1 ? (nSay == 1 ? "您已超速，時速" + Math.round(spd) + "，限速" + L + "，請減速。" : "請減速，限速" + L + "。")
                            : lv == 2 ? "超速" + by + "公里，請減速！" : "危險！超速" + by + "公里，立刻減速！";
                    speak(out, m, now);
                }
                lastSay = now;
            }
        } else if (over) {
            over = false; lv = 0;
            if (now >= busyUntil) speak(out, "已回到限速內，請保持。", now);
        }
        isOver = ov;
        level = ov ? lv : 0;
    }
}
