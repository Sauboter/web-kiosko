package kz.saubot.webkiosko;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/** Меню администратора: сайт, PIN, блокировка, разрешения, сеанс посетителя, звук, график. */
public class SettingsActivity extends Activity {

    /** Киоск временно «на паузе» (администратор вышел в Android). Сбрасывается кнопкой «Save and start kiosk». */
    static boolean paused = false;

    private static final String TEXT = "#10263A";
    private static final String MUTED = "#5A7184";
    private static final String ACCENT = "#14A098";

    private EditText url, domains, authUser, authPass, pin, idleMin, reloadMin;
    private CheckBox allowHttp, mic, camera, location, pinScreen, clearOnIdle, allowBack, allowZoom;
    private CheckBox autoVolume, autoBright, lockKeys, schOn, schAutoWake;
    private SeekBar volumeBar, brightBar;
    private final CheckBox[] dayOn = new CheckBox[7];
    private final EditText[] dayStart = new EditText[7];
    private final EditText[] dayEnd = new EditText[7];
    private RadioGroup orient;
    private TextView status;
    private Button exactAlarmButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        box.setPadding(pad, pad, pad, pad);
        scroll.addView(box);
        setContentView(scroll);
        fitSystemBars(scroll);

        boolean firstRun = !Prefs.isConfigured(this);

        TextView title = new TextView(this);
        title.setText(firstRun ? "Welcome to Web Kiosko" : "Web Kiosko settings");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor(TEXT));
        box.addView(title);

        if (firstRun) {
            hint(box, "Web Kiosko shows one website full screen and keeps visitors inside it.\n\n"
                    + "1. Enter the website address and choose an admin PIN.\n"
                    + "2. Tap \"Save and start kiosk\".\n"
                    + "3. To come back to these settings later, tap 5 times quickly in the top-left corner "
                    + "of the screen and enter the PIN.\n\n"
                    + "Remember the PIN: without it the settings can only be reset by reinstalling the app.");
        }

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setTextColor(Color.parseColor(MUTED));
        status.setPadding(0, dp(6), 0, dp(6));
        box.addView(status);

        // ---------- сайт ----------
        section(box, "Website");
        url = field(box, "Start page address", Prefs.startUrl(this), InputType.TYPE_TEXT_VARIATION_URI);
        url.setHint("https://example.com");
        domains = multiField(box, "Other allowed domains (optional)", Prefs.domainsRaw(this));
        domains.setHint("pay.example.com, login.example.com");
        hint(box, "Visitors can only open pages on the start page's domain and the domains listed here "
                + "(subdomains included). Add domains used for sign-in or payment if the website needs them.");
        allowHttp = check(box, "Allow insecure http:// pages (for local network websites)", Prefs.allowHttp(this));
        authUser = field(box, "Website login (only if the browser asks for one)", Prefs.authUser(this),
                InputType.TYPE_TEXT_VARIATION_NORMAL);
        authPass = field(box, "Website password", Prefs.authPass(this), InputType.TYPE_TEXT_VARIATION_PASSWORD);

        // ---------- доступ ----------
        section(box, "Admin access");
        pin = field(box, "Admin PIN (4–8 digits)", Prefs.pin(this),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        label(box, "Screen orientation");
        orient = new RadioGroup(this);
        orient.setOrientation(RadioGroup.HORIZONTAL);
        String[] names = {"Auto", "Portrait", "Landscape"};
        for (int i = 0; i < names.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(names[i]);
            rb.setId(100 + i);
            orient.addView(rb);
        }
        orient.check(100 + Prefs.orientation(this));
        box.addView(orient);
        pinScreen = check(box, "Pin the screen (Android screen pinning; use if device owner mode is not set up)",
                Prefs.pinScreen(this));

        // ---------- разрешения сайту ----------
        section(box, "Website permissions");
        hint(box, "Turn on only what your website needs. Android will ask once to confirm.");
        mic = check(box, "Microphone", Prefs.mic(this));
        camera = check(box, "Camera", Prefs.camera(this));
        location = check(box, "Location", Prefs.location(this));

        // ---------- сеанс посетителя ----------
        section(box, "Visitor session");
        idleMin = field(box, "Return to the start page after this many minutes without touches (0 = never)",
                String.valueOf(Prefs.idleMinutes(this)), InputType.TYPE_CLASS_NUMBER);
        clearOnIdle = check(box, "Also clear cookies and website data (signs the visitor out)", Prefs.clearOnIdle(this));
        reloadMin = field(box, "Reload the page every N minutes (0 = never)",
                String.valueOf(Prefs.reloadMinutes(this)), InputType.TYPE_CLASS_NUMBER);
        allowBack = check(box, "Android Back button goes back to the previous page", Prefs.allowBack(this));
        allowZoom = check(box, "Allow pinch to zoom", Prefs.allowZoom(this));

        // ---------- звук, яркость, кнопки ----------
        section(box, "Sound and screen");
        label(box, "Volume");
        autoVolume = check(box, "Don't change (use Android volume)", Prefs.volume(this) < 0);
        volumeBar = seek(box, Prefs.volume(this) < 0 ? 70 : Prefs.volume(this));
        volumeBar.setEnabled(!autoVolume.isChecked());
        autoVolume.setOnCheckedChangeListener((v, on) -> volumeBar.setEnabled(!on));
        label(box, "Brightness");
        autoBright = check(box, "Don't change (use Android brightness)", Prefs.brightness(this) < 0);
        brightBar = seek(box, Prefs.brightness(this) < 0 ? 70 : Prefs.brightness(this));
        brightBar.setEnabled(!autoBright.isChecked());
        autoBright.setOnCheckedChangeListener((v, on) -> brightBar.setEnabled(!on));
        lockKeys = check(box, "Block the hardware volume and brightness buttons (volume stays at the level above)",
                Prefs.lockKeys(this));

        // ---------- рабочее время ----------
        section(box, "Screen schedule");
        schOn = check(box, "Turn the screen off outside working hours (it turns on again automatically)",
                Schedule.enabled(this));
        for (int d = 0; d < 7; d++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(2), 0, dp(2));
            dayOn[d] = new CheckBox(this);
            dayOn[d].setText(Schedule.DAY_NAMES[d]);
            dayOn[d].setChecked(Schedule.dayOn(this, d));
            dayOn[d].setMinWidth(dp(80));
            row.addView(dayOn[d]);
            dayStart[d] = timeField(Schedule.fmt(Schedule.start(this, d)));
            row.addView(dayStart[d]);
            TextView dash = new TextView(this);
            dash.setText("  —  ");
            dash.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            row.addView(dash);
            dayEnd[d] = timeField(Schedule.fmt(Schedule.end(this, d)));
            row.addView(dayEnd[d]);
            box.addView(row);
        }
        hint(box, "Checked = working day. Time in 24-hour format, e.g. 08:00. Unchecked days: the screen stays off.");
        schAutoWake = check(box, "During working hours, turn the screen back on 1 minute after someone switches it off",
                Schedule.autoWake(this));
        exactAlarmButton = button(box, "Allow exact alarms (screen turns on exactly on time)", false,
                v -> openExactAlarmSettings());

        button(box, "Save and start kiosk", true, v -> saveAndOpen());

        // ---------- лицензия ----------
        section(box, "License");
        hint(box, Prefs.isPro(this)
                ? "Full version: no watermark."
                : "Free version: all features work, a small \"Free version\" label is shown on the screen.\n"
                  + "Removing the label will be available as an in-app purchase.");

        // ---------- обслуживание ----------
        section(box, "Maintenance");
        button(box, "Make Web Kiosko the home app", false, v -> openAndroid(new Intent(Settings.ACTION_HOME_SETTINGS)));
        button(box, "App permissions", false, v -> openAndroid(
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        button(box, "How to enable full lock (device owner mode)", false, v -> showDeviceOwnerHelp());
        button(box, "Exit kiosk to Android settings", false, v -> openAndroid(new Intent(Settings.ACTION_SETTINGS)));
        if (Kiosk.isDeviceOwner(this)) {
            button(box, "Turn off device owner mode", false, v -> confirmRemoveOwner());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderStatus();
        exactAlarmButton.setVisibility(Schedule.canExact(this) ? View.GONE : View.VISIBLE);
    }

    private void renderStatus() {
        StringBuilder s = new StringBuilder("Version " + Kiosk.version(this));
        s.append(Prefs.isPro(this) ? " · Full version" : " · Free version");
        s.append("\nLock: ").append(Kiosk.isDeviceOwner(this) ? "full (device owner)"
                : Kiosk.isDefaultHome(this) ? "home app" : "none — the app can be closed");
        if (Prefs.mic(this) && !has(Manifest.permission.RECORD_AUDIO)) s.append("\nMicrophone: not allowed in Android yet");
        if (Prefs.camera(this) && !has(Manifest.permission.CAMERA)) s.append("\nCamera: not allowed in Android yet");
        if (Prefs.location(this) && !has(Manifest.permission.ACCESS_FINE_LOCATION)
                && !has(Manifest.permission.ACCESS_COARSE_LOCATION)) s.append("\nLocation: not allowed in Android yet");
        if (Schedule.enabled(this)) {
            s.append("\nSchedule: ").append(Schedule.isWorkNow(this) ? "working hours" : "outside working hours")
                    .append(", next change ").append(Schedule.nextStartText(this));
            if (!Schedule.canExact(this)) s.append(" (may be a few minutes late — allow exact alarms below)");
        }
        if (Prefs.isConfigured(this)) s.append("\nWebsite: ").append(Prefs.startUrl(this));
        status.setText(s.toString());
    }

    private boolean has(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void saveAndOpen() {
        String u = Prefs.normalizeUrl(url.getText().toString());
        String p = pin.getText().toString().trim();
        if (u.isEmpty()) {
            toast("Enter the start page address, for example https://example.com");
            return;
        }
        if (u.toLowerCase().startsWith("http://") && !allowHttp.isChecked()) {
            toast("This address uses http://. Turn on \"Allow insecure http:// pages\" or use https://");
            return;
        }
        if (!p.matches("\\d{4,8}")) {
            toast("The PIN must be 4 to 8 digits");
            return;
        }
        int idle = number(idleMin), reload = number(reloadMin);
        if (idle < 0 || idle > 1440 || reload < 0 || reload > 1440) {
            toast("Minutes must be a number from 0 to 1440");
            return;
        }
        boolean[] days = new boolean[7];
        int[] starts = new int[7], ends = new int[7];
        for (int d = 0; d < 7; d++) {
            days[d] = dayOn[d].isChecked();
            starts[d] = Schedule.parse(dayStart[d].getText().toString());
            ends[d] = Schedule.parse(dayEnd[d].getText().toString());
            if (days[d] && (starts[d] < 0 || ends[d] < 0 || starts[d] == ends[d])) {
                toast(Schedule.DAY_NAMES[d] + ": check the time, e.g. 08:00 — 20:00");
                return;
            }
            if (starts[d] < 0) starts[d] = Schedule.start(this, d);
            if (ends[d] < 0) ends[d] = Schedule.end(this, d);
        }

        int o = orient.getCheckedRadioButtonId() - 100;
        SharedPreferences.Editor e = Prefs.sp(this).edit();
        e.putString("url", u);
        e.putString("domains", String.join(", ", Prefs.parseDomains(domains.getText().toString())));
        e.putBoolean("allowHttp", allowHttp.isChecked());
        e.putString("authUser", authUser.getText().toString().trim());
        e.putString("authPass", authPass.getText().toString());
        e.putString("pin", p);
        e.putInt("orientation", o < 0 || o > 2 ? Prefs.ORIENT_AUTO : o);
        e.putBoolean("pinScreen", pinScreen.isChecked());
        e.putBoolean("mic", mic.isChecked());
        e.putBoolean("camera", camera.isChecked());
        e.putBoolean("location", location.isChecked());
        e.putInt("idleMin", idle);
        e.putBoolean("clearOnIdle", clearOnIdle.isChecked());
        e.putInt("reloadMin", reload);
        e.putBoolean("allowBack", allowBack.isChecked());
        e.putBoolean("allowZoom", allowZoom.isChecked());
        e.putInt("volume", autoVolume.isChecked() ? -1 : volumeBar.getProgress());
        e.putInt("brightness", autoBright.isChecked() ? -1 : brightBar.getProgress());
        e.putBoolean("lockKeys", lockKeys.isChecked());
        e.apply();
        Schedule.save(this, schOn.isChecked(), schAutoWake.isChecked(), days, starts, ends);
        Schedule.scheduleNext(this);

        paused = false;
        Kiosk.applyOwnerPolicies(this);
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    private int number(EditText f) {
        String s = f.getText().toString().trim();
        if (s.isEmpty()) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException ex) { return -1; }
    }

    /** Чтобы открыть системные экраны, сначала снимаем блокировку (до кнопки «Save and start kiosk»). */
    private void openAndroid(Intent intent) {
        paused = true;
        try { stopLockTask(); } catch (Exception ignored) { }
        Kiosk.releaseOwnerPolicies(this);
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception ex) {
            toast("This screen is not available on this device");
        }
    }

    private void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < 31) return;
        openAndroid(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())));
    }

    private void showDeviceOwnerHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Full lock (device owner mode)")
                .setMessage("Device owner mode hides the Home, Recents and notification buttons and the power menu, "
                        + "so visitors cannot leave the kiosk.\n\n"
                        + "It is enabled once with a computer:\n"
                        + "1. Remove all accounts from the device (Settings → Accounts) or use a freshly reset device.\n"
                        + "2. Turn on Developer options (tap \"Build number\" 7 times) and USB debugging.\n"
                        + "3. Install Android platform-tools (adb) on the computer and connect the device by USB.\n"
                        + "4. Run:\n"
                        + "adb shell dpm set-device-owner " + getPackageName() + "/.KioskAdminReceiver\n\n"
                        + "You should see \"Success: Device owner set\". After that you can add a Google account again.\n"
                        + "To turn it off, use \"Turn off device owner mode\" in this menu.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void confirmRemoveOwner() {
        new AlertDialog.Builder(this)
                .setTitle("Turn off device owner mode?")
                .setMessage("The full lock will be removed and the device will work as usual. "
                        + "Turning it on again requires a computer (adb).")
                .setPositiveButton("Turn off", (d, w) -> {
                    paused = true;
                    try { stopLockTask(); } catch (Exception ignored) { }
                    Kiosk.removeDeviceOwner(this);
                    toast("Device owner mode is off");
                    recreate();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------- отступы от системных панелей (Android 15+ рисует под ними) ----------

    @SuppressWarnings("deprecation")
    private void fitSystemBars(View v) {
        if (Build.VERSION.SDK_INT >= 30) {
            v.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime()
                        | WindowInsets.Type.displayCutout());
                view.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            });
        } else {
            v.setFitsSystemWindows(true);
        }
    }

    // ---------- элементы формы ----------

    private void section(LinearLayout box, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.parseColor(ACCENT));
        t.setPadding(0, dp(28), 0, dp(2));
        box.addView(t);
    }

    private void label(LinearLayout box, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.parseColor(TEXT));
        t.setPadding(0, dp(14), 0, dp(4));
        box.addView(t);
    }

    private void hint(LinearLayout box, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(Color.parseColor(MUTED));
        t.setPadding(0, dp(6), 0, dp(4));
        box.addView(t);
    }

    private EditText field(LinearLayout box, String caption, String value, int variation) {
        label(box, caption);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        int cls = (variation & InputType.TYPE_MASK_CLASS) != 0 ? 0 : InputType.TYPE_CLASS_TEXT;
        e.setInputType(cls | variation);
        e.setText(value);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private EditText multiField(LinearLayout box, String caption, String value) {
        label(box, caption);
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_VARIATION_URI);
        e.setMinLines(2);
        e.setText(value);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private CheckBox check(LinearLayout box, String text, boolean value) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setChecked(value);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        c.setPadding(0, dp(8), 0, dp(8));
        box.addView(c);
        return c;
    }

    private Button button(LinearLayout box, String text, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        if (primary) {
            b.setBackgroundColor(Color.parseColor(ACCENT));
            b.setTextColor(Color.WHITE);
        }
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(primary ? 24 : 8);
        box.addView(b, lp);
        return b;
    }

    private SeekBar seek(LinearLayout box, int value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(value);
        TextView pct = new TextView(this);
        pct.setText(value + "%");
        pct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        pct.setMinWidth(dp(56));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean fromUser) { pct.setText(p + "%"); }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        row.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(pct);
        box.addView(row);
        return bar;
    }

    private EditText timeField(String value) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        e.setText(value);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        e.setMinWidth(dp(84));
        e.setGravity(android.view.Gravity.CENTER);
        return e;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
