package com.nsxl.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Watches other apps' media sessions (Spotify, YouTube Music, podcasts…) so NSXL's ambient music can
 * step aside while they play and come back when they pause. Android only shares media sessions with
 * apps that have notification access, which is what {@link MediaListener} is for. NSXL only looks at
 * whether something is playing; it never reads notifications.
 */
final class MediaWatch {
    interface Listener { void onChange(boolean playing, String pkg); }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<MediaController> controllers = new ArrayList<>();
    private MediaSessionManager msm;
    private boolean started, known, playing;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { check(); }
        @Override public void onSessionDestroyed() { check(); }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::attach;

    MediaWatch(Context c, Listener l) {
        ctx = c.getApplicationContext();
        listener = l;
    }

    static ComponentName component(Context c) {
        return new ComponentName(c, MediaListener.class);
    }

    /** True once the user has turned on notification access for NSXL. */
    static boolean allowed(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(c.getPackageName() + "/");
    }

    /** The system page where the user turns notification access on for NSXL. */
    static Intent settingsIntent(Context c) {
        if (Build.VERSION.SDK_INT >= 30) {
            return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(c).flattenToString());
        }
        return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
    }

    /** Starts watching if access is allowed. Safe to call again (on every resume, say). */
    void start() {
        if (started || !allowed(ctx)) return;
        try {
            msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return;
            msm.addOnActiveSessionsChangedListener(sessions, component(ctx), main);
            started = true;
            attach(msm.getActiveSessions(component(ctx)));
        } catch (SecurityException e) {
            started = false;   // access was turned off again
        }
    }

    void stop() {
        if (msm != null) {
            try { msm.removeOnActiveSessionsChangedListener(sessions); } catch (Exception ignored) { }
        }
        for (MediaController c : controllers) c.unregisterCallback(callback);
        controllers.clear();
        started = false;
    }

    boolean playing() { return playing; }

    private void attach(List<MediaController> list) {
        for (MediaController c : controllers) c.unregisterCallback(callback);
        controllers.clear();
        if (list != null) {
            for (MediaController c : list) {
                if (ctx.getPackageName().equals(c.getPackageName())) continue;   // never ourselves
                c.registerCallback(callback, main);
                controllers.add(c);
            }
        }
        check();
    }

    private void check() {
        String pkg = null;
        for (MediaController c : controllers) {
            PlaybackState s = c.getPlaybackState();
            if (s == null) continue;
            int st = s.getState();
            if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) { pkg = c.getPackageName(); break; }
        }
        boolean on = pkg != null;
        if (!known || on != playing) {
            known = true;
            playing = on;
            listener.onChange(on, pkg);
        }
    }
}
