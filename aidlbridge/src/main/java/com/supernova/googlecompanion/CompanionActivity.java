package com.supernova.googlecompanion;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

public final class CompanionActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView message = new TextView(this);
        message.setText(
                "Supernova Google Companion\n\n"
                        + "Служебный компонент Google Discover.\n"
                        + "Управление доступно в настройках Supernova Launcher."
        );
        message.setTextSize(18);
        message.setTextColor(Color.WHITE);
        message.setGravity(Gravity.CENTER);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        message.setPadding(padding, padding, padding, padding);
        message.setBackgroundColor(Color.rgb(24, 45, 59));
        setContentView(message);
    }
}
