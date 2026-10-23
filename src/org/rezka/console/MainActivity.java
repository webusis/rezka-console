package org.rezka.console;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Запуск/останов плавающей консоли. Само окно живёт в ConsoleService. */
public class MainActivity extends Activity {

    private TextView status;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setGravity(Gravity.CENTER);
        ll.setPadding(32, 48, 32, 32);
        ll.setBackgroundColor(Color.BLACK);

        TextView t = new TextView(this);
        t.setText("ПЛАВАЮЩАЯ КОНСОЛЬ\n\nокно поверх всех приложений (в т.ч. MX Player).\n"
                + "На Android 4.x разрешение выдаётся при установке — подтверждать не надо.\n\n"
                + "Перетаскивание — за заголовок.\n$ — шелл, # — root (su), — / + — свернуть, × — закрыть.");
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 0, 0, 32);

        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, 0, 0, 24);

        Button start = new Button(this);
        start.setText("ПОКАЗАТЬ КОНСОЛЬ");
        start.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                startService(new Intent(MainActivity.this, ConsoleService.class));
                refresh();
            }
        });

        Button stop = new Button(this);
        stop.setText("СКРЫТЬ КОНСОЛЬ");
        stop.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                stopService(new Intent(MainActivity.this, ConsoleService.class));
                refresh();
            }
        });

        ll.addView(t);
        ll.addView(status, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        ll.addView(start, lp());
        ll.addView(stop, lp());
        setContentView(ll);
    }

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (52 * getResources().getDisplayMetrics().density));
        l.setMargins(0, 8, 0, 8);
        return l;
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean on = ConsoleService.running;
        status.setText(on ? "● консоль работает" : "○ консоль скрыта");
        status.setTextColor(on ? Color.rgb(80, 220, 120) : Color.rgb(220, 120, 120));
    }
}