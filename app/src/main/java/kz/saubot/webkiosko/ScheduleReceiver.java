package kz.saubot.webkiosko;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Будильник графика: начало или конец рабочего времени, либо автовключение экрана. */
public class ScheduleReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        boolean work = Schedule.isWorkNow(c);

        if (Schedule.ACTION_WAKE.equals(action)) {
            if (work && !SettingsActivity.paused) Schedule.wakeScreen(c);
            return;
        }

        MainActivity m = MainActivity.current();
        if (m != null) m.runOnUiThread(m::applySchedule);
        if (work) {
            Schedule.wakeScreen(c);
        } else if (!SettingsActivity.paused) {
            Schedule.sleepScreen(c);
        }
        Schedule.scheduleNext(c);
    }
}
