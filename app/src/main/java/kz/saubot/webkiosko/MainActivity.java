package kz.saubot.webkiosko;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.HttpAuthHandler;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Экран киоска: выбранный сайт на весь экран. Выйти можно только через скрытое меню
 * (5 быстрых касаний в левом верхнем углу + PIN администратора).
 */
public class MainActivity extends Activity {

    private static final int REQ_STARTUP = 10;
    private static final int REQ_WEB = 11;
    private static final int REQ_GEO = 12;
    private static final long RETRY_MS = 15_000;
    private static final long WATERMARK_MOVE_MS = 120_000;
    private static final long RELOAD_QUIET_MS = 60_000;   // автообновление — только если минуту никто не касался
    private static final int SECRET_TAPS = 5;
    private static final long SECRET_WINDOW_MS = 4_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView web;
    private TextView offline, night, watermark;
    private boolean offlineOpensSetup = false;
    private String loadedUrl = "";
    private String loadedSettingsKey = "";
    private int authAttempts = 0;
    private boolean loadFailed = false;
    private boolean clearHistoryOnLoad = false;
    private int taps = 0;
    private long firstTapAt = 0;
    private long lastTouchAt = System.currentTimeMillis();
    private boolean touchedSinceHome = false;
    private boolean askedStartupPermissions = false;
    private long lastBlockedToastAt = 0;
    private int watermarkCorner = 3;
    private PermissionRequest pendingWeb;
    private List<String> pendingWebResources;
    private GeolocationPermissions.Callback pendingGeo;
    private String pendingGeoOrigin;
    private ConnectivityManager.NetworkCallback netCallback;
    private boolean isNight = false;
    private BroadcastReceiver screenOffReceiver;
    private static WeakReference<MainActivity> currentRef = new WeakReference<>(null);

    private final Runnable retry = this::reloadKiosk;
    private final Runnable nightSleep = () -> Schedule.sleepScreen(this);
    private final Runnable keepVolume = new Runnable() {
        @Override public void run() {
            applyVolume(false);
            handler.postDelayed(this, 10_000);
        }
    };
    private final Runnable idleCheck = new Runnable() {
        @Override public void run() { checkIdle(); }
    };
    private final Runnable autoReload = new Runnable() {
        @Override public void run() { doAutoReload(); }
    };
    private final Runnable moveWatermark = new Runnable() {
        @Override public void run() {
            watermarkCorner = (watermarkCorner + 1) % 4;
            placeWatermark();
            handler.postDelayed(this, WATERMARK_MOVE_MS);
        }
    };

    /** Открытый экран киоска (для будильника графика). */
    static MainActivity current() {
        return currentRef.get();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        currentRef = new WeakReference<>(this);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        // Android 13+: кнопка/жест «Назад» приходит сюда, а не в onBackPressed
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        setContentView(root);
        createWebView();
        createWatermark();
        createOfflineView();
        createNightView();
        createSecretCorner();

        Kiosk.applyOwnerPolicies(this);
        watchNetwork();
        watchScreenOff();
        Schedule.scheduleNext(this);

        if (!Prefs.isConfigured(this)) {
            startActivity(new Intent(this, SettingsActivity.class));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Kiosk.applyOrientation(this);
        hideSystemUi();
        updateWatermark();
        if (!Prefs.isConfigured(this)) {
            offlineOpensSetup = true;
            showOffline("Web Kiosko is not set up yet.\n\nTap here to start setup.", false);
            return;
        }
        offlineOpensSetup = false;
        applySchedule();
        applyVolume(true);
        handler.removeCallbacks(keepVolume);
        handler.postDelayed(keepVolume, 10_000);
        requestEnabledPermissions();

        // Блокировка: у «владельца устройства» — всегда; иначе — закрепление экрана, если включено
        if (!SettingsActivity.paused && !Kiosk.isInLockTask(this)
                && (Kiosk.isDeviceOwner(this) || Prefs.pinScreen(this))) {
            try { startLockTask(); } catch (Exception ignored) { }
        }

        String key = settingsKey();
        if (!key.equals(loadedSettingsKey)) {
            loadedSettingsKey = key;
            applyWebSettings();
            goHome(false);
        }
        restartTimers();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(keepVolume);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (currentRef.get() == this) currentRef = new WeakReference<>(null);
        if (screenOffReceiver != null) {
            try { unregisterReceiver(screenOffReceiver); } catch (Exception ignored) { }
        }
        if (netCallback != null) {
            try {
                ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCallback);
            } catch (Exception ignored) { }
        }
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** Каждое касание — посетитель на месте: сбрасываем таймер бездействия. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            lastTouchAt = System.currentTimeMillis();
            touchedSinceHome = true;
            scheduleIdleCheck();
        }
        return super.dispatchTouchEvent(ev);
    }

    /** Кнопки громкости и яркости на корпусе не работают, пока открыт киоск (если включено). */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (Prefs.lockKeys(this) && !SettingsActivity.paused) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_VOLUME_UP:
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                case KeyEvent.KEYCODE_VOLUME_MUTE:
                case KeyEvent.KEYCODE_MUTE:
                case KeyEvent.KEYCODE_BRIGHTNESS_UP:
                case KeyEvent.KEYCODE_BRIGHTNESS_DOWN:
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        handleBack();   // Android 8–12
    }

    /** «Назад»: по умолчанию ничего; если разрешено — назад по страницам сайта. */
    private void handleBack() {
        if (Prefs.allowBack(this) && web != null && web.canGoBack()) web.goBack();
    }

    // ---------- WebView ----------

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView() {
        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setLongClickable(false);
        web.setOnLongClickListener(v -> true);       // без меню «копировать / выделить»
        web.setHapticFeedbackEnabled(false);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);           // target=_blank открывается в том же окне
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setGeolocationEnabled(true);
        s.setTextZoom(100);                           // крупный системный шрифт не ломает вёрстку сайта
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " WebKiosko/" + Kiosk.version(this));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setDownloadListener((url, ua, cd, mime, len) -> blockedToast("Downloads are disabled on this kiosk"));

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> handleWebPermission(request));
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                runOnUiThread(() -> handleGeo(origin, callback));
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Встроенные фреймы (видео, карты, оплата) не ограничиваем — только переходы всей страницы
                if (!request.isForMainFrame()) return false;
                if (Prefs.isAllowed(MainActivity.this, request.getUrl())) return false;
                blockedToast("This page is not available on this kiosk");
                return true;
            }

            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler h, String host, String realm) {
                String user = Prefs.authUser(MainActivity.this);
                if (!Prefs.isAllowedHost(MainActivity.this, host) || user.isEmpty()) { h.cancel(); return; }
                authAttempts++;
                if (authAttempts > 3) {
                    loadFailed = true;
                    h.cancel();
                    showOffline("Wrong login or password for this website.\n\n"
                            + "Open settings: tap 5 times quickly in the top-left corner.", false);
                    return;
                }
                h.proceed(user, Prefs.authPass(MainActivity.this));
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler h, SslError error) {
                h.cancel();   // небезопасное соединение никогда не открываем
                // Ошибка у самой страницы (а не у картинки или скрипта) — показываем сообщение
                String bad = error.getUrl();
                boolean mainPage = bad != null && (bad.equals(view.getUrl()) || bad.equals(loadedUrl) || view.getUrl() == null);
                if (mainPage) {
                    loadFailed = true;
                    showOffline("Secure connection failed: the website's certificate is not valid.\n\n"
                            + "Retrying in 15 seconds…", true);
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                loadFailed = false;
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                if (!loadFailed) {
                    authAttempts = 0;
                    hideOffline();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (clearHistoryOnLoad) {
                    clearHistoryOnLoad = false;
                    view.clearHistory();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    loadFailed = true;
                    showOffline("No connection to the website.\n\nRetrying in 15 seconds…", true);
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                recreate();   // движок страницы упал — пересоздаём экран, киоск вернётся сам
                return true;
            }
        });

        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Настройки, которые зависят от меню администратора. */
    private void applyWebSettings() {
        WebSettings s = web.getSettings();
        boolean zoom = Prefs.allowZoom(this);
        s.setSupportZoom(zoom);
        s.setBuiltInZoomControls(zoom);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(Prefs.allowHttp(this)
                ? WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE : WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    }

    /** Если в меню поменяли адрес или параметры страницы — открываем стартовую страницу заново. */
    private String settingsKey() {
        return Prefs.startUrl(this) + "|" + Prefs.domainsRaw(this) + "|" + Prefs.allowHttp(this) + "|"
                + Prefs.allowZoom(this) + "|" + Prefs.authUser(this) + "|" + Prefs.authPass(this);
    }

    private void reloadKiosk() {
        if (!Prefs.isConfigured(this)) return;
        authAttempts = 0;
        loadedUrl = Prefs.startUrl(this);
        web.loadUrl(loadedUrl);
    }

    /** Вернуться на стартовую страницу; при необходимости стереть всё, что оставил посетитель. */
    private void goHome(boolean clearData) {
        if (!Prefs.isConfigured(this)) return;
        if (clearData) {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            WebStorage.getInstance().deleteAllData();
            web.clearCache(true);
            web.clearFormData();
        }
        clearHistoryOnLoad = true;
        touchedSinceHome = false;
        reloadKiosk();
    }

    private void blockedToast(String text) {
        long now = System.currentTimeMillis();
        if (now - lastBlockedToastAt < 3_000) return;
        lastBlockedToastAt = now;
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    // ---------- бездействие и автообновление ----------

    private void restartTimers() {
        scheduleIdleCheck();
        handler.removeCallbacks(autoReload);
        int r = Prefs.reloadMinutes(this);
        if (r > 0) handler.postDelayed(autoReload, r * 60_000L);
    }

    private void scheduleIdleCheck() {
        handler.removeCallbacks(idleCheck);
        int m = Prefs.idleMinutes(this);
        if (m > 0) handler.postDelayed(idleCheck, m * 60_000L);
    }

    private void checkIdle() {
        int m = Prefs.idleMinutes(this);
        if (m <= 0) return;
        long left = lastTouchAt + m * 60_000L - System.currentTimeMillis();
        if (left > 1_000) { handler.postDelayed(idleCheck, left); return; }
        // Посетитель ушёл: возвращаемся на стартовую страницу (только если он что-то трогал)
        if (touchedSinceHome && !isNight && !SettingsActivity.paused) goHome(Prefs.clearOnIdle(this));
    }

    private void doAutoReload() {
        int r = Prefs.reloadMinutes(this);
        if (r <= 0) return;
        boolean quiet = System.currentTimeMillis() - lastTouchAt >= RELOAD_QUIET_MS;
        if (quiet && !isNight && offline.getVisibility() != View.VISIBLE) web.reload();
        handler.postDelayed(autoReload, quiet ? r * 60_000L : RELOAD_QUIET_MS);
    }

    // ---------- разрешения (микрофон, камера, геолокация) ----------

    private boolean has(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    /** Спрашиваем разрешения Android сразу после настройки, а не при первом посетителе. */
    private void requestEnabledPermissions() {
        if (askedStartupPermissions || SettingsActivity.paused) return;
        List<String> need = new ArrayList<>();
        if (Prefs.mic(this) && !has(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO);
        if (Prefs.camera(this) && !has(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA);
        if (Prefs.location(this) && !has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (need.isEmpty()) return;
        askedStartupPermissions = true;
        requestPermissions(need.toArray(new String[0]), REQ_STARTUP);
    }

    /** Какое разрешение Android нужно для ресурса страницы (null — не нужно; "" — запрещено настройками). */
    private String androidPermFor(String resource) {
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
            return Prefs.mic(this) ? Manifest.permission.RECORD_AUDIO : "";
        }
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
            return Prefs.camera(this) ? Manifest.permission.CAMERA : "";
        }
        if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(resource)) return null;   // защищённое видео
        return "";
    }

    private void handleWebPermission(PermissionRequest request) {
        Uri origin = request.getOrigin();
        if (origin == null || !Prefs.isAllowedHost(this, origin.getHost())) { request.deny(); return; }
        List<String> grant = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String r : request.getResources()) {
            String perm = androidPermFor(r);
            if ("".equals(perm)) continue;
            grant.add(r);
            if (perm != null && !has(perm) && !missing.contains(perm)) missing.add(perm);
        }
        if (grant.isEmpty()) { request.deny(); return; }
        if (missing.isEmpty()) { request.grant(grant.toArray(new String[0])); return; }
        if (pendingWeb != null) pendingWeb.deny();
        pendingWeb = request;
        pendingWebResources = grant;
        requestPermissions(missing.toArray(new String[0]), REQ_WEB);
    }

    private void handleGeo(String origin, GeolocationPermissions.Callback callback) {
        Uri u = Uri.parse(origin);
        if (!Prefs.location(this) || !Prefs.isAllowedHost(this, u.getHost())) {
            callback.invoke(origin, false, false);
            return;
        }
        if (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            callback.invoke(origin, true, false);
            return;
        }
        pendingGeo = callback;
        pendingGeoOrigin = origin;
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_GEO);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_WEB && pendingWeb != null) {
            PermissionRequest r = pendingWeb;
            List<String> ok = new ArrayList<>();
            for (String res : pendingWebResources) {
                String perm = androidPermFor(res);
                if (perm == null || (!perm.isEmpty() && has(perm))) ok.add(res);
            }
            pendingWeb = null;
            pendingWebResources = null;
            if (ok.isEmpty()) r.deny(); else r.grant(ok.toArray(new String[0]));
        } else if (requestCode == REQ_GEO && pendingGeo != null) {
            boolean ok = has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION);
            pendingGeo.invoke(pendingGeoOrigin, ok, false);
            pendingGeo = null;
            pendingGeoOrigin = null;
        }
    }

    // ---------- звук и яркость ----------

    private void applyVolume(boolean force) {
        int v = Prefs.volume(this);
        if (v < 0 || (!force && !Prefs.lockKeys(this))) return;
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        for (int stream : new int[]{AudioManager.STREAM_MUSIC, AudioManager.STREAM_VOICE_CALL}) {
            try {
                int target = Math.round(am.getStreamMaxVolume(stream) * v / 100f);
                if (am.getStreamVolume(stream) != target) am.setStreamVolume(stream, target, 0);
            } catch (Exception ignored) { }
        }
    }

    // ---------- водяной знак бесплатной версии ----------

    private void createWatermark() {
        watermark = new TextView(this);
        watermark.setText("Web Kiosko · Free version");
        watermark.setTextColor(Color.argb(230, 255, 255, 255));
        watermark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        watermark.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(150, 0, 0, 0));
        bg.setCornerRadius(dp(16));
        watermark.setBackground(bg);
        watermark.setClickable(false);    // касания проходят сквозь знак к сайту
        watermark.setFocusable(false);
        watermark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(watermark, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        placeWatermark();
    }

    /** Знак переезжает из угла в угол, чтобы его нельзя было закрыть наклейкой. */
    private void placeWatermark() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) watermark.getLayoutParams();
        int v = watermarkCorner < 2 ? Gravity.TOP : Gravity.BOTTOM;
        int h = watermarkCorner == 0 || watermarkCorner == 3 ? Gravity.START : Gravity.END;
        lp.gravity = v | h;
        lp.setMargins(dp(12), dp(12), dp(12), dp(12));
        watermark.setLayoutParams(lp);
    }

    private void updateWatermark() {
        handler.removeCallbacks(moveWatermark);
        if (Prefs.isPro(this)) {
            watermark.setVisibility(View.GONE);
        } else {
            watermark.setVisibility(View.VISIBLE);
            handler.postDelayed(moveWatermark, WATERMARK_MOVE_MS);
        }
    }

    // ---------- нет связи / не настроено ----------

    private void createOfflineView() {
        offline = new TextView(this);
        offline.setBackgroundColor(Color.parseColor("#1F3A5F"));
        offline.setTextColor(Color.WHITE);
        offline.setGravity(Gravity.CENTER);
        offline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        int pad = dp(32);
        offline.setPadding(pad, pad, pad, pad);
        offline.setVisibility(View.GONE);
        offline.setClickable(true);    // касания не доходят до сайта под сообщением
        offline.setOnClickListener(v -> {
            if (offlineOpensSetup) startActivity(new Intent(this, SettingsActivity.class));
        });
        root.addView(offline, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showOffline(String text, boolean autoRetry) {
        offline.setText(text);
        offline.setVisibility(View.VISIBLE);
        handler.removeCallbacks(retry);
        if (autoRetry) handler.postDelayed(retry, RETRY_MS);
    }

    private void hideOffline() {
        handler.removeCallbacks(retry);
        offline.setVisibility(View.GONE);
    }

    private void watchNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return;
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                // Интернет вернулся — пробуем сразу, не дожидаясь 15 секунд
                handler.post(() -> {
                    if (offline.getVisibility() == View.VISIBLE && !offlineOpensSetup) reloadKiosk();
                });
            }
        };
        try { cm.registerDefaultNetworkCallback(netCallback); } catch (Exception ignored) { }
    }

    // ---------- график работы экрана ----------

    private void createNightView() {
        night = new TextView(this);
        night.setBackgroundColor(Color.BLACK);
        night.setTextColor(Color.parseColor("#7A8A99"));
        night.setGravity(Gravity.CENTER);
        night.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        int pad = dp(32);
        night.setPadding(pad, pad, pad, pad);
        night.setVisibility(View.GONE);
        night.setClickable(true);
        root.addView(night, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Рабочее время — сайт, экран не гаснет. Нерабочее — чёрный экран, через 20 с экран выключается. */
    void applySchedule() {
        boolean work = Schedule.isWorkNow(this);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        handler.removeCallbacks(nightSleep);
        if (work) {
            night.setVisibility(View.GONE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            int b = Prefs.brightness(this);
            lp.screenBrightness = b < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : Math.max(0.05f, b / 100f);
            getWindow().setAttributes(lp);
            if (isNight) {
                isNight = false;
                goHome(Prefs.clearOnIdle(this));   // утром — свежая стартовая страница
            }
        } else {
            isNight = true;
            night.setText("Outside working hours\n\nThe screen will turn on " + Schedule.nextStartText(this));
            night.setVisibility(View.VISIBLE);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            lp.screenBrightness = 0.01f;
            getWindow().setAttributes(lp);
            if (!SettingsActivity.paused) handler.postDelayed(nightSleep, 20_000);
        }
    }

    /** Экран погасили кнопкой питания в рабочее время — через минуту включим снова. */
    private void watchScreenOff() {
        screenOffReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (!SettingsActivity.paused) Schedule.scheduleAutoWake(c);
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOffReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenOffReceiver, f);
    }

    // ---------- скрытое меню администратора ----------

    @SuppressLint("ClickableViewAccessibility")
    private void createSecretCorner() {
        View corner = new View(this);
        corner.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                long now = System.currentTimeMillis();
                if (now - firstTapAt > SECRET_WINDOW_MS) { taps = 0; firstTapAt = now; }
                if (++taps >= SECRET_TAPS) { taps = 0; askPin(); }
            }
            return true;
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(72), dp(72));
        lp.gravity = Gravity.TOP | Gravity.START;
        root.addView(corner, lp);
    }

    private void askPin() {
        if (!Prefs.isConfigured(this)) {
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("Admin PIN");
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Kiosk settings")
                .setView(input)
                .setPositiveButton("Open", (dlg, w) -> {
                    if (input.getText().toString().equals(Prefs.pin(this))) {
                        startActivity(new Intent(this, SettingsActivity.class));
                    } else {
                        Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnDismissListener(x -> hideSystemUi());
        d.show();
        // Окно закроется само через 30 секунд, чтобы посетитель не остался с полем PIN
        handler.postDelayed(() -> { if (d.isShowing()) d.dismiss(); }, 30_000);
    }

    // ---------- весь экран ----------

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
