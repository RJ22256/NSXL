package com.nsxl.launcher;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Build;
import android.view.Surface;
import android.view.TextureView;

import java.io.File;
import java.io.FileInputStream;

/** Plays the live-wallpaper video on a silent loop, scaled to fill the screen (centre-crop). */
final class VideoWallpaperView extends TextureView implements TextureView.SurfaceTextureListener {

    private MediaPlayer player;
    private Surface surface;
    private File file;
    private boolean wantPlay;
    private int videoW, videoH;

    VideoWallpaperView(Context c) {
        super(c);
        setSurfaceTextureListener(this);
    }

    void setVideoFile(File f) {
        file = f;
        if (player != null) {
            releasePlayer();
            if (wantPlay) open();
        }
    }

    void play() {
        wantPlay = true;
        if (player != null) {
            try {
                if (!player.isPlaying()) player.start();
            } catch (Exception ignored) { }
            return;
        }
        open();
    }

    void stop() {
        wantPlay = false;
        releasePlayer();
    }

    void setBlur(int radius) {
        if (Build.VERSION.SDK_INT >= 31) {
            setRenderEffect(radius > 0 ? RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) : null);
        }
    }

    private void open() {
        if (player != null || surface == null || file == null || !file.exists()) return;
        final MediaPlayer mp = new MediaPlayer();
        try (FileInputStream fis = new FileInputStream(file)) {
            mp.setDataSource(fis.getFD());
        } catch (Exception e) {
            mp.release();
            return;
        }
        mp.setSurface(surface);
        mp.setLooping(true);
        mp.setVolume(0f, 0f);
        mp.setOnPreparedListener(p -> {
            if (p != player) return;
            videoW = p.getVideoWidth();
            videoH = p.getVideoHeight();
            updateTransform();
            if (wantPlay) p.start();
        });
        mp.setOnVideoSizeChangedListener((p, w, h) -> {
            videoW = w;
            videoH = h;
            updateTransform();
        });
        mp.setOnErrorListener((p, what, extra) -> {
            releasePlayer();
            return true;
        });
        player = mp;
        try {
            mp.prepareAsync();
        } catch (Exception e) {
            releasePlayer();
        }
    }

    private void releasePlayer() {
        if (player == null) return;
        MediaPlayer p = player;
        player = null;
        try {
            p.stop();
        } catch (Exception ignored) { }
        p.release();
    }

    private void updateTransform() {
        float vw = getWidth(), vh = getHeight();
        if (vw <= 0 || vh <= 0 || videoW <= 0 || videoH <= 0) return;
        float viewRatio = vw / vh, videoRatio = videoW / (float) videoH;
        float sx = 1f, sy = 1f;
        if (videoRatio > viewRatio) sx = videoRatio / viewRatio;
        else sy = viewRatio / videoRatio;
        Matrix m = new Matrix();
        m.setScale(sx, sy, vw / 2f, vh / 2f);
        setTransform(m);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateTransform();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        surface = new Surface(st);
        updateTransform();
        if (wantPlay) open();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
        updateTransform();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        releasePlayer();
        if (surface != null) {
            surface.release();
            surface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) { }
}
