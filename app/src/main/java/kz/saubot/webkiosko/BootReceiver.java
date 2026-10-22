package kz.saubot.webkiosko;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** После включения устройства или обновления приложения — открываем киоск. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Schedule.scheduleNext(context);   // после перезагрузки, обновления или смены времени — заново ставим будильник графика
        String a = intent.getAction();
        if (Intent.ACTION_TIME_CHANGED.equals(a) || Intent.ACTION_TIMEZONE_CHANGED.equals(a)) return;
        try {
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Exception ignored) {
            // Android 10+ может не разрешить запуск из фона. Тогда киоск откроется как «главный экран».
        }
    }
}
