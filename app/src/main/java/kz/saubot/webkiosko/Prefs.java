package kz.saubot.webkiosko;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Настройки устройства (вводятся в меню администратора). */
final class Prefs {

    static final int ORIENT_AUTO = 0;
    static final int ORIENT_PORTRAIT = 1;
    static final int ORIENT_LANDSCAPE = 2;

    private Prefs() {}

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("webkiosko", Context.MODE_PRIVATE);
    }

    // ---------- сайт ----------
    static String startUrl(Context c) { return sp(c).getString("url", "").trim(); }
    static String domainsRaw(Context c) { return sp(c).getString("domains", ""); }
    static boolean allowHttp(Context c) { return sp(c).getBoolean("allowHttp", false); }
    static String authUser(Context c) { return sp(c).getString("authUser", ""); }
    static String authPass(Context c) { return sp(c).getString("authPass", ""); }

    // ---------- разрешения сайту ----------
    static boolean mic(Context c) { return sp(c).getBoolean("mic", false); }
    static boolean camera(Context c) { return sp(c).getBoolean("camera", false); }
    static boolean location(Context c) { return sp(c).getBoolean("location", false); }

    // ---------- доступ ----------
    static String pin(Context c) { return sp(c).getString("pin", ""); }
    static boolean pinScreen(Context c) { return sp(c).getBoolean("pinScreen", false); }
    static int orientation(Context c) { return sp(c).getInt("orientation", ORIENT_AUTO); }

    // ---------- сеанс посетителя ----------
    /** Через сколько минут без касаний вернуться на стартовую страницу (0 — никогда). */
    static int idleMinutes(Context c) { return sp(c).getInt("idleMin", 0); }
    static boolean clearOnIdle(Context c) { return sp(c).getBoolean("clearOnIdle", false); }
    /** Перезагружать страницу раз в N минут (0 — нет). */
    static int reloadMinutes(Context c) { return sp(c).getInt("reloadMin", 0); }
    static boolean allowBack(Context c) { return sp(c).getBoolean("allowBack", false); }
    static boolean allowZoom(Context c) { return sp(c).getBoolean("allowZoom", false); }

    // ---------- звук, экран, кнопки ----------
    /** Громкость 0–100 (−1 — как в Android), яркость 0–100 (−1 — как в Android). */
    static int volume(Context c) { return sp(c).getInt("volume", -1); }
    static int brightness(Context c) { return sp(c).getInt("brightness", -1); }
    static boolean lockKeys(Context c) { return sp(c).getBoolean("lockKeys", true); }

    // ---------- лицензия ----------
    /** Платная версия (без водяного знака). Включается покупкой — появится в версии 0.2. */
    static boolean isPro(Context c) { return sp(c).getBoolean("pro", false); }

    static boolean isConfigured(Context c) {
        return !startUrl(c).isEmpty() && !pin(c).isEmpty();
    }

    static String host(Context c) {
        String h = Uri.parse(startUrl(c)).getHost();
        return h == null ? "" : h.toLowerCase(Locale.ROOT);
    }

    /** Добавляет https://, если схема не указана. Пустая строка, если адрес неверный. */
    static String normalizeUrl(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.isEmpty()) return "";
        String lower = s.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) s = "https://" + s;
        Uri u = Uri.parse(s);
        if (u.getHost() == null || u.getHost().isEmpty() || !u.getHost().contains(".") && !"localhost".equals(u.getHost())) {
            return "";
        }
        return s;
    }

    /** "https://*.shop.com/path, cdn.example.com:8080" → [shop.com, cdn.example.com] */
    static List<String> parseDomains(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String d : raw.split("[,;\\s]+")) {
            d = d.trim().toLowerCase(Locale.ROOT);
            int i = d.indexOf("://");
            if (i >= 0) d = d.substring(i + 3);
            i = d.indexOf('/');
            if (i >= 0) d = d.substring(0, i);
            i = d.indexOf(':');
            if (i >= 0) d = d.substring(0, i);
            while (d.startsWith("*.") || d.startsWith(".")) d = d.substring(d.startsWith("*.") ? 2 : 1);
            if (!d.isEmpty() && !out.contains(d)) out.add(d);
        }
        return out;
    }

    /** Разрешённые домены: домен стартовой страницы + список из настроек. Поддомены разрешены тоже. */
    static List<String> allowedDomains(Context c) {
        List<String> list = parseDomains(domainsRaw(c));
        String h = host(c);
        if (!h.isEmpty() && !list.contains(h)) list.add(0, h);
        return list;
    }

    static boolean isAllowedHost(Context c, String host) {
        if (host == null || host.isEmpty()) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String d : allowedDomains(c)) {
            if (host.equals(d) || host.endsWith("." + d)) return true;
        }
        return false;
    }

    static boolean isAllowed(Context c, Uri u) {
        if (u == null) return false;
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        boolean schemeOk = "https".equals(scheme) || ("http".equals(scheme) && allowHttp(c));
        return schemeOk && isAllowedHost(c, u.getHost());
    }
}
