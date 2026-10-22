package kz.saubot.webkiosko;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;

import java.util.Calendar;
import java.util.Locale;

/**
 * Рабочее время киоска. Вне графика экран выключается, в начале рабочего дня включается сам.
 * Будильник Android (AlarmManager) будит устройство даже в глубоком сне.
 */
final class Schedule {

    static final String ACTION_TICK = "kz.saubot.webkiosko.SCHEDULE_TICK";
    static final String ACTION_WAKE = "kz.saubot.webkiosko.WAKE";
    static final String[] DAY_NAMES = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    static final long AUTO_WAKE_DELAY_MS = 60_000;   // через сколько включать экран, если его погасили кнопкой

    private static final int REQ_TICK = 1;
    private static final int REQ_WAKE = 2;

    private Schedule() {}

    private static SharedPreferences sp(Context c) {
        return Prefs.sp(c);
    }

    // ---------- настройки ----------

    static boolean enabled(Context c) { return sp(c).getBoolean("sch_on", false); }
    static boolean autoWake(Context c) { return sp(c).getBoolean("sch_autowake", true); }

    static boolean dayOn(Context c, int d) { return sp(c).getBoolean("sch_d" + d + "_on", d <= 5); }
    /** Начало и конец в минутах от полуночи. По умолчанию: будни 8:00–20:00, суббота 9:00–14:00. */
    static int start(Context c, int d) { return sp(c).getInt("sch_d" + d + "_s", d == 5 ? 9 * 60 : 8 * 60); }
    static int end(Context c, int d) { return sp(c).getInt("sch_d" + d + "_e", d == 5 ? 14 * 60 : 20 * 60); }

    static void save(Context c, boolean on, boolean autoWake, boolean[] days, int[] starts, int[] ends) {
        SharedPreferences.Editor e = sp(c).edit();
        e.putBoolean("sch_on", on);
        e.putBoolean("sch_autowake", autoWake);
        for (int d = 0; d < 7; d++) {
            e.putBoolean("sch_d" + d + "_on", days[d]);
            e.putInt("sch_d" + d + "_s", starts[d]);
            e.putInt("sch_d" + d + "_e", ends[d]);
        }
        e.apply();
    }

    static String fmt(int minutes) {
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    /** "08:30" → 510; неверный формат → -1 */
    static int parse(String s) {
        if (s == null) return -1;
        s = s.trim().replace('.', ':');
        if (!s.matches("\\d{1,2}:\\d{2}")) return -1;
        String[] p = s.split(":");
        int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
        if (h > 24 || m > 59 || (h == 24 && m > 0)) return -1;
        return h * 60 + m;
    }

    // ---------- расчёт ----------

    /** Понедельник = 0 … воскресенье = 6 */
    static int dayIndex(Calendar cal) {
        return (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }

    static boolean isWorkTime(Context c, Calendar cal) {
        if (!enabled(c)) return true;
        int d = dayIndex(cal);
        if (!dayOn(c, d)) return false;
        int m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        int s = start(c, d), e = end(c, d);
        return s < e ? (m >= s && m < e) : (m >= s || m < e);
    }

    static boolean isWorkNow(Context c) {
        return isWorkTime(c, Calendar.getInstance());
    }

    /** Ближайшая минута, когда рабочее время начнётся или закончится (поиск на 8 дней вперёд). */
    static long nextChange(Context c, long nowMs) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(nowMs);
        boolean now = isWorkTime(c, cal);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 8 * 24 * 60; i++) {
            cal.add(Calendar.MINUTE, 1);
            if (isWorkTime(c, cal) != now) return cal.getTimeInMillis();
        }
        return nowMs + 24L * 3600_000L;
    }

    /** Текст «включится в 08:00» / «в Пн 08:00» для экрана нерабочего времени. */
    static String nextStartText(Context c) {
        long t = nextChange(c, System.currentTimeMillis());
        Calendar now = Calendar.getInstance();
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(t);
        String time = fmt(cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE));
        boolean sameDay = cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR);
        Calendar tomorrow = (Calendar) now.clone();
        tomorrow.add(Calendar.DAY_OF_YEAR, 1);
        if (sameDay) return "today at " + time;
        if (cal.get(Calendar.DAY_OF_YEAR) == tomorrow.get(Calendar.DAY_OF_YEAR)) return "tomorrow at " + time;
        return "on " + DAY_NAMES[dayIndex(cal)] + " at " + time;
    }

    // ---------- будильники ----------

    static void scheduleNext(Context c) {
        cancel(c, ACTION_TICK, REQ_TICK);
        if (!enabled(c)) return;
        setAlarm(c, nextChange(c, System.currentTimeMillis()) + 1000, ACTION_TICK, REQ_TICK);
    }

    /** Экран погасили кнопкой в рабочее время — включим его снова через минуту. */
    static void scheduleAutoWake(Context c) {
        if (!autoWake(c) || !isWorkNow(c)) return;
        setAlarm(c, System.currentTimeMillis() + AUTO_WAKE_DELAY_MS, ACTION_WAKE, REQ_WAKE);
    }

    /** Разрешены ли точные будильники (на Android 14+ их включает пользователь в настройках). */
    static boolean canExact(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    private static PendingIntent pending(Context c, String action, int req) {
        Intent i = new Intent(c, ScheduleReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void setAlarm(Context c, long at, String action, int req) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(c, action, req);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    private static void cancel(Context c, String action, int req) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(c, action, req));
    }

    // ---------- экран ----------

    /** Включить экран и показать киоск. */
    @SuppressWarnings("deprecation")
    static void wakeScreen(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "webkiosko:wake");
            wl.acquire(15_000);
        } catch (Exception ignored) {
        }
        try {
            Intent i = new Intent(c, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            c.startActivity(i);
        } catch (Exception ignored) {
            // Android может не разрешить запуск из фона — экран всё равно включён, киоск уже на экране
        }
    }

    /** Выключить экран сразу (только у «владельца устройства»; иначе гаснет по тайм-ауту системы). */
    static void sleepScreen(Context c) {
        if (!Kiosk.isDeviceOwner(c)) return;
        try {
            Kiosk.dpm(c).lockNow();
        } catch (Exception ignored) {
        }
    }
}
