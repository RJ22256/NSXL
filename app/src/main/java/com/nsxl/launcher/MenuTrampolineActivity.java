package com.nsxl.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

/**
 * Invisible, instant activity used by the Quick Settings tile and the notification:
 * opening an activity closes the notification shade, then we show the menu and finish.
 */
public class MenuTrampolineActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else {
            getSharedPreferences("launcher", MODE_PRIVATE).edit().putBoolean("overlay", true).apply();
            OverlayService.start(this, OverlayService.ACTION_SHOW);
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
