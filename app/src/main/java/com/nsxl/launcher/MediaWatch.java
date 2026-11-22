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

    /** The session worth showing: whatever's playing, else the most recent one. */
    private MediaController current() {
        for (MediaController c : controllers) {
            PlaybackState s = c.getPlaybackState();
            if (s != null && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING)) return c;
        }
        return controllers.isEmpty() ? null : controllers.get(0);
    }

    /** Title, artist, app and progress of the current session (album art too, if asked), or null. */
    org.json.JSONObject now(boolean withArt) throws org.json.JSONException {
        MediaController c = current();
        if (c == null) return null;
        android.media.MediaMetadata md = c.getMetadata();
        if (md == null) return null;
        String title = md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE);
        if (title == null) title = md.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (title == null || title.isEmpty()) return null;
        org.json.JSONObject o = new org.json.JSONObject();
        o.put("title", title);
        String artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null) artist = md.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        o.put("artist", artist == null ? "" : artist);
        o.put("album", md.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM));
        o.put("pkg", c.getPackageName());
        String app = c.getPackageName();
        try { app = ctx.getPackageManager().getApplicationLabel(ctx.getPackageManager().getApplicationInfo(c.getPackageName(), 0)).toString(); } catch (Exception ignored) { }
        o.put("app", app);
        PlaybackState s = c.getPlaybackState();
        boolean on = s != null && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING);
        o.put("playing", on);
        long dur = md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION);
        if (s != null) {
            long pos = s.getPosition();
            if (on && s.getLastPositionUpdateTime() > 0) pos += (long) ((android.os.SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
            o.put("position", Math.max(0, pos) / 1000.0);
        }
        o.put("duration", Math.max(0, dur) / 1000.0);
        if (withArt) {
            android.graphics.Bitmap b = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (b != null) {
                android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(b, 360, Math.max(1, Math.round(360f * b.getHeight() / Math.max(1, b.getWidth()))), true);
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bos);
                o.put("art", "data:image/jpeg;base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP));
            }
        }
        return o;
    }

    /** play (toggle), next or prev on the current session. */
    void command(String c) {
        MediaController m = current();
        if (m == null) return;
        MediaController.TransportControls t = m.getTransportControls();
        PlaybackState s = m.getPlaybackState();
        boolean on = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        if ("play".equals(c) || "toggle".equals(c)) { if (on) t.pause(); else t.play(); }
        else if ("next".equals(c)) t.skipToNext();
        else if ("prev".equals(c)) t.skipToPrevious();
    }

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
