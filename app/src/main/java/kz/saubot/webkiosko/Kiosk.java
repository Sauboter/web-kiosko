package kz.saubot.webkiosko;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.Settings;

/** Режим «владелец устройства» (Device Owner), блокировка экрана и общие проверки. */
final class Kiosk {

    private Kiosk() {}

    static ComponentName admin(Context c) {
        return new ComponentName(c, KioskAdminReceiver.class);
    }

    static DevicePolicyManager dpm(Context c) {
        return (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    static boolean isDeviceOwner(Context c) {
        DevicePolicyManager d = dpm(c);
        return d != null && d.isDeviceOwnerApp(c.getPackageName());
    }

    static boolean isInLockTask(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        return am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
    }

    /** Включаем ограничения, если приложение — владелец устройства. */
    static void applyOwnerPolicies(Context c) {
        if (!isDeviceOwner(c)) return;
        DevicePolicyManager d = dpm(c);
        ComponentName a = admin(c);
        try {
            d.setLockTaskPackages(a, new String[]{c.getPackageName()});
            if (Build.VERSION.SDK_INT >= 28) {
                d.setLockTaskFeatures(a, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
            }
            d.setKeyguardDisabled(a, true);
            d.setStatusBarDisabled(a, true);
            d.setGlobalSetting(a, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, String.valueOf(
                    BatteryManager.BATTERY_PLUGGED_AC | BatteryManager.BATTERY_PLUGGED_USB
                            | BatteryManager.BATTERY_PLUGGED_WIRELESS));
            // Разрешения, которые администратор включил для сайта, выдаём без вопросов
            if (Prefs.mic(c)) grant(d, a, c, Manifest.permission.RECORD_AUDIO);
            if (Prefs.camera(c)) grant(d, a, c, Manifest.permission.CAMERA);
            if (Prefs.location(c)) {
                grant(d, a, c, Manifest.permission.ACCESS_FINE_LOCATION);
                grant(d, a, c, Manifest.permission.ACCESS_COARSE_LOCATION);
            }

            IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addCategory(Intent.CATEGORY_DEFAULT);
            d.addPersistentPreferredActivity(a, home, new ComponentName(c, MainActivity.class));
        } catch (Exception ignored) {
        }
    }

    private static void grant(DevicePolicyManager d, ComponentName a, Context c, String perm) {
        try {
            d.setPermissionGrantState(a, c.getPackageName(), perm, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
        } catch (Exception ignored) {
        }
    }

    /** Снимаем ограничения (выход в настройки Android для обслуживания). */
    static void releaseOwnerPolicies(Context c) {
        if (!isDeviceOwner(c)) return;
        DevicePolicyManager d = dpm(c);
        ComponentName a = admin(c);
        try {
            d.setStatusBarDisabled(a, false);
            d.setKeyguardDisabled(a, false);
            d.clearPackagePersistentPreferredActivities(a, c.getPackageName());
        } catch (Exception ignored) {
        }
    }

    /** Полностью отдать устройство обратно. */
    static void removeDeviceOwner(Context c) {
        if (!isDeviceOwner(c)) return;
        releaseOwnerPolicies(c);
        try {
            dpm(c).setLockTaskPackages(admin(c), new String[0]);
            dpm(c).clearDeviceOwnerApp(c.getPackageName());
        } catch (Exception ignored) {
        }
    }

    static boolean isDefaultHome(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo r = c.getPackageManager().resolveActivity(i, 0);
        return r != null && r.activityInfo != null && c.getPackageName().equals(r.activityInfo.packageName);
    }

    static String version(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return p.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    static void applyOrientation(Activity a) {
        int o = Prefs.orientation(a);
        a.setRequestedOrientation(o == Prefs.ORIENT_PORTRAIT ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : o == Prefs.ORIENT_LANDSCAPE ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }
}
