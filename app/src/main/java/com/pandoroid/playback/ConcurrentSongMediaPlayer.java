package com.pandoroid.playback;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;

import com.pandoroid.Pandoroid;
import com.pandoroid.pandora.PandoraAudioUrl;
import com.pandoroid.pandora.Song;

public class ConcurrentSongMediaPlayer {

    private final ExoPlayer m_player;

    private Song m_song;
    private PandoraAudioUrl m_url;

    private volatile boolean m_alive;
    private volatile boolean m_buffering;
    private volatile boolean m_playbackComplete;

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    @OptIn(markerClass = UnstableApi.class)
    public ConcurrentSongMediaPlayer() {

        m_player = new ExoPlayer.Builder(
                Pandoroid.getContext())
                .build();
        initializeAudioAttributes();

        //AudioAttributes attrs =
        //        new AudioAttributes.Builder()
        //                .setUsage(C.USAGE_MEDIA)
        //                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        //                .build();
        //m_player.setAudioAttributes(attrs, true);
        m_player.addAnalyticsListener(
                new AnalyticsListener() {

                    @Override
                    public void onAudioUnderrun(
                            @NonNull EventTime eventTime,
                            int bufferSize,
                            long bufferSizeMs,
                            long elapsedSinceLastFeedMs) {

                        Log.e("Pandoroid",
                                "AUDIO UNDERRUN");
                    }
                });
        m_player.addListener(new Player.Listener() {
            @Override
            public void onEvents(@NonNull Player player, @NonNull Player.Events events) {

                Log.i(
                        "Pandoroid",
                        "isPlaying=" + player.isPlaying()
                                + " state=" + player.getPlaybackState()
                                + " volume=" + player.getVolume()
                                + " playWhenReady=" + player.getPlayWhenReady()
                );
            }
            @Override
            public void onPlayerError(
                    @NonNull PlaybackException error) {

                Log.e("Pandoroid",
                        "PLAYER ERROR",
                        error);
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                Log.i("Pandoroid", "onIsPlayingChanged: " + isPlaying);
            }

            @Override
            public void onPlayWhenReadyChanged(
                    boolean playWhenReady,
                    int reason) {

                Log.i("Pandoroid",
                        "playWhenReady="
                                + playWhenReady
                                + " reason="
                                + reason);
            }

            @Override
            public void onVolumeChanged(float volume) {
                Log.i("Pandoroid", "volume: " + volume);
            }

            @Override
            public void onAudioSessionIdChanged(int audioSessionId) {
                Log.i("Pandoroid", "audioSessionId = " + audioSessionId);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                Log.e("Pandoroid", "state: " + state);
                Log.e(
                        "Pandoroid",
                        "onPlaybackStateChanged state="
                                + state
                                + " thread="
                                + Thread.currentThread().getName());
                switch (state) {

                    case Player.STATE_BUFFERING:
                        m_buffering = true;
                        break;

                    case Player.STATE_READY:
                        m_buffering = false;
                        break;

                    case Player.STATE_ENDED:
                        m_playbackComplete = true;
                        break;
                }
            }

            @Override
            public void onMediaItemTransition(
                    @Nullable MediaItem mediaItem,
                    int reason) {

                Log.i("Pandoroid",
                        "onMediaItemTransition reason="
                                + reason);
            }

        });
    }

    public void initializeAudioAttributes() {

        CountDownLatch latch = new CountDownLatch(1);

        mainHandler.post(() -> {

            AudioAttributes attrs =
                    new AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build();

            m_player.setAudioAttributes(attrs, true);
            m_player.setWakeMode(C.WAKE_MODE_NETWORK);

            latch.countDown();
        });

        try {
            latch.await();
        } catch (InterruptedException ignored) {
        }
    }

    public void prepare(PandoraAudioUrl url) {

        m_url = url;

        m_buffering = false;
        m_playbackComplete = false;

        CountDownLatch latch = new CountDownLatch(1);

        mainHandler.post(() -> {

            MediaMetadata.Builder metadataBuilder = new MediaMetadata.Builder();
            if (m_song != null) {
                if (m_song.getTitle() != null) {
                    metadataBuilder.setTitle(m_song.getTitle())
                            .setDisplayTitle(m_song.getTitle());
                }
                if (m_song.getArtist() != null) {
                    metadataBuilder.setArtist(m_song.getArtist());
                }
                if (m_song.getAlbum() != null) {
                    metadataBuilder.setAlbumTitle(m_song.getAlbum());
                }
                if (m_song.getAlbumCoverUrl() != null && !m_song.getAlbumCoverUrl().isEmpty()) {
                    metadataBuilder.setArtworkUri(Uri.parse(m_song.getAlbumCoverUrl()));
                }
            }

            MediaItem item = new MediaItem.Builder()
                    .setUri(url.toString())
                    .setMediaMetadata(metadataBuilder.build())
                    .build();

            m_player.setMediaItem(item);

            m_player.prepare();

            latch.countDown();
        });

        try {
            latch.await();
        }
        catch (InterruptedException ignored) {
        }

        m_alive = true;
    }

    public void start() {

        mainHandler.post(m_player::play);
    }

    public void pause() {

        mainHandler.post(m_player::pause);
    }

    public void release() {

        mainHandler.post(m_player::release);

        m_alive = false;
    }

    public void reset() {

        mainHandler.post(() -> {
            m_player.stop();
            m_player.clearMediaItems();
        });

        m_alive = false;
    }

    public void seekTo(int msec) {

        mainHandler.post(() -> {
            m_player.seekTo(msec);
        });
    }

    public int getCurrentPosition() {

        if (Looper.myLooper() == mainHandler.getLooper()) {
            return (int) m_player.getCurrentPosition();
        }

        AtomicInteger result = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);

        mainHandler.post(() -> {
            result.set((int) m_player.getCurrentPosition());
            latch.countDown();
        });

        try {
            latch.await();
        } catch (InterruptedException ignored) {
        }

        return result.get();
    }

    public int getDuration() {

        AtomicInteger result = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);

        mainHandler.post(() -> {
            result.set((int) m_player.getDuration());
            latch.countDown();
        });

        try {
            latch.await();
        } catch (InterruptedException ignored) {
        }

        return result.get();
    }

    public boolean isPlaying() {

        if (Looper.myLooper() == Looper.getMainLooper()) {
            return m_player.isPlaying();
        }

        AtomicBoolean result = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        mainHandler.post(() -> {
            result.set(m_player.isPlaying());
            latch.countDown();
        });

        try {
            latch.await();
        } catch (InterruptedException ignored) {
        }

        return result.get();
    }

    public boolean isAlive() {
        return m_alive;
    }

    public boolean isBuffering() {
        return m_buffering;
    }

    public boolean isPlaybackComplete() {
        return m_playbackComplete;
    }

    public void setBuffering(boolean buffering) {
        m_buffering = buffering;
    }

    @OptIn(markerClass = UnstableApi.class)
    public int getAudioSessionId() {

        AtomicInteger result = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);

        mainHandler.post(() -> {
            result.set(m_player.getAudioSessionId());
            latch.countDown();
        });

        try {
            latch.await();
        } catch (InterruptedException ignored) {
        }

        return result.get();
    }


    public Song getSong() {
        return m_song;
    }

    public void setSong(Song song) {
        m_song = song;
    }

    public PandoraAudioUrl getUrl() {
        return m_url;
    }

    public ExoPlayer getExoPlayer() {
        return m_player;
    }

    public void clearPlaybackComplete() {
        m_playbackComplete = false;
    }

    public String getDebugStateMainThread() {

        return "playing=" + m_player.isPlaying()
                + ", state=" + m_player.getPlaybackState()
                + ", ready=" + m_player.getPlayWhenReady()
                + ", volume=" + m_player.getVolume()
                + ", position=" + m_player.getCurrentPosition();
    }
}