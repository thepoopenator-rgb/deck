package com.fulldeck.player;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.IntentSender;
import android.net.Uri;
import android.provider.MediaStore;
import java.util.Collections;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/** One-screen player: load your local songs, shuffle each exactly once, then reshuffle. */
public final class MainActivity extends Activity implements PlaybackService.Listener {
    private static final int REQUEST_MUSIC = 5;
    private static final int REQUEST_NOTIFICATIONS = 6;
    private static final int REQUEST_DELETE_AUDIO = 7;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable updateClock = new Runnable() {
        @Override public void run() { drawClock(); handler.postDelayed(this, 180); }
    };
    private PlaybackService service;
    private boolean bound;
    private boolean bindingRequested;
    private boolean touchingSeek;
    private boolean rescanAfterConfirmedDelete;
    private TextView title, artist, counter, progress, summary, elapsed, duration, status;
    private Button play;
    private SeekBar seek;
    private LinearLayout upcoming;
    private TurntableView turntable;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlaybackService.LocalBinder)binder).getService();
            bound = true;
            service.setListener(MainActivity.this);
            if (service.hasAudioPermission()) {
                if (rescanAfterConfirmedDelete || (!service.hasScanned() && !service.isScanning())) {
                    rescanAfterConfirmedDelete = false;
                    service.scanMusic();
                }
            } else requestMusicPermission();
            render();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false; service = null; render();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        title = findViewById(R.id.title);
        artist = findViewById(R.id.artist);
        counter = findViewById(R.id.counter);
        progress = findViewById(R.id.progress);
        summary = findViewById(R.id.summary);
        elapsed = findViewById(R.id.elapsed);
        duration = findViewById(R.id.duration);
        status = findViewById(R.id.status);
        play = findViewById(R.id.play);
        seek = findViewById(R.id.seek);
        upcoming = findViewById(R.id.upcoming);
        turntable = findViewById(R.id.turntable);

        play.setOnClickListener(v -> sendCommand(PlaybackService.ACTION_TOGGLE));
        findViewById(R.id.next).setOnClickListener(v -> sendCommand(PlaybackService.ACTION_NEXT));
        findViewById(R.id.random).setOnClickListener(v -> {
            if (service == null || service.isScanning()) return;
            // Keep the currently playing track out of the random candidates.
            service.randomNext();
        });
        findViewById(R.id.delete).setOnClickListener(v -> showDeleteChoices());
        findViewById(R.id.previous).setOnClickListener(v -> sendCommand(PlaybackService.ACTION_PREV));
        findViewById(R.id.scan).setOnClickListener(v -> {
            if (service != null) {
                if (service.hasAudioPermission()) service.scanMusic();
                else requestMusicPermission();
            }
        });
        findViewById(R.id.reshuffle).setOnClickListener(v -> {
            if (service == null || service.totalCount() == 0) return;
            new AlertDialog.Builder(this)
                    .setTitle("Start a new deck?")
                    .setMessage("This resets your no-repeat progress. Songs heard in the old deck may play again.")
                    .setNegativeButton("Keep this deck", null)
                    .setPositiveButton("New deck", (dialog, which) -> {
                        service.startNewDeck();
                        sendCommand(PlaybackService.ACTION_PLAY);
                    }).show();
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar bar) { touchingSeek = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                touchingSeek = false;
                if (service != null) service.seekTo(bar.getProgress());
            }
        });
    }

    @Override public void onStart() {
        super.onStart();
        bindingRequested = bindService(new Intent(this,PlaybackService.class), connection, Context.BIND_AUTO_CREATE);
        handler.post(updateClock);
    }
    @Override public void onStop() {
        handler.removeCallbacks(updateClock);
        if (bound && service != null) service.clearListener(this);
        if (bindingRequested) unbindService(connection);
        bindingRequested = false;
        bound = false;
        service = null;
        super.onStop();
    }

    private void requestMusicPermission() {
        String permission = Build.VERSION.SDK_INT >= 33 ?
                Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] { permission }, REQUEST_MUSIC);
        else if (service != null) service.scanMusic();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_MUSIC) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                if (service != null) service.scanMusic();
                if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, REQUEST_NOTIFICATIONS);
            } else {
                status.setText("Allow Music access in Android Settings to play local MP3s.");
            }
        }
    }

    private void sendCommand(String action) {
        if (service != null && service.totalCount() == 0) {
            if (service.hasAudioPermission() && !service.isScanning()) service.scanMusic();
            render();
            return;
        }
        Intent intent = new Intent(this, PlaybackService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
    }

    @Override public void onPlayerChanged() { runOnUiThread(this::render); }

    private void render() {
        if (service == null) return;
        Song song = service.currentSong();
        int total = service.totalCount();
        title.setText(song == null ? "Your music. All of it." : song.title);
        artist.setText(song == null ? "Local MP3 player" :
                (song.folder.isEmpty() ? song.artist : song.artist + "  •  " + song.folder));
        counter.setText(total == 0 ? "READY TO SHUFFLE" :
                String.format(Locale.US, "TRACK %,d OF %,d", service.playedCount(), total));
        progress.setText(total == 0 ? "No repeats. No forgotten songs." :
                String.format(Locale.US, "%,d songs still waiting", service.remainingCount()));
        summary.setText(total == 0 ? "Your shuffled deck stays saved, even after closing the app." :
                "Every track plays once before the deck reshuffles.");
        play.setText(service.isPlaying() ? "Ⅱ" : "▶");
        play.setContentDescription(service.isPlaying() ? "Pause" : "Play");
        if (service.isScanning()) {
            status.setText("Scanning music in all folders and subfolders…");
        } else if (!service.error().isEmpty()) {
            status.setText(service.error());
        } else {
            status.setText(String.format(Locale.US, "%,d local tracks found, including subfolders. Offline and private.",
                    service.libraryCount()));
        }
        upcoming.removeAllViews();
        List<Song> soon = service.upNext(4);
        if (soon.isEmpty()) {
            addUpcoming("New deck begins after this song", "", 0);
        } else {
            for (int i = 0; i < soon.size(); i++) {
                Song candidate = soon.get(i);
                addUpcoming(candidate.title, candidate.artist, i+1);
            }
        }
        drawClock();
    }

    private void addUpcoming(String trackTitle, String trackArtist, int order) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(10), 0, dp(10));
        TextView rank = new TextView(this);
        rank.setText(order == 0 ? "↻" : String.valueOf(order));
        rank.setTextColor(getColor(R.color.mint));
        rank.setTextSize(13f);
        rank.setGravity(Gravity.CENTER);
        rank.setWidth(dp(28));
        line.addView(rank);
        LinearLayout descriptions = new LinearLayout(this);
        descriptions.setOrientation(LinearLayout.VERTICAL);
        descriptions.setPadding(dp(10),0,0,0);
        TextView heading = new TextView(this);
        heading.setText(trackTitle);
        heading.setMaxLines(1);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.setTextColor(getColor(R.color.text));
        heading.setTextSize(14f);
        descriptions.addView(heading);
        if (!trackArtist.isEmpty()) {
            TextView subtitle = new TextView(this);
            subtitle.setText(trackArtist);
            subtitle.setTextColor(getColor(R.color.muted));
            subtitle.setTextSize(12f);
            descriptions.addView(subtitle);
        }
        line.addView(descriptions, new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1));
        upcoming.addView(line);
    }

    private void showDeleteChoices() {
        if (service == null) return;
        Song song = service.currentSong();
        if (song == null) return;
        new AlertDialog.Builder(this)
                .setTitle(song.title)
                .setItems(new String[] {"Remove from this shuffle only (keep MP3)",
                        "Delete MP3 permanently from phone"}, (dialog, selection) -> {
                    if (selection == 0) {
                        service.removeCurrentFromDeck();
                    } else {
                        confirmPermanentDelete(song);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmPermanentDelete(Song song) {
        if (Build.VERSION.SDK_INT < 30) {
            new AlertDialog.Builder(this)
                    .setTitle("Delete on this Android version")
                    .setMessage("Full Deck supports Android-confirmed permanent deletion on Android 11+ (API 30+). " +
                            "On older phones, delete the MP3 in your file manager and tap Scan Music.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String location = song.folder.isEmpty() ? "" : "\nFolder: " + song.folder;
        new AlertDialog.Builder(this)
                .setTitle("Permanently delete this MP3?")
                .setMessage(song.title + location +
                        "\n\nThis removes the real music file from your phone, NOT just Full Deck. " +
                        "Android will ask for final approval.")
                .setNegativeButton("Keep file", null)
                .setPositiveButton("Continue to Android confirmation", (dialog, which) -> {
                    try {
                        Uri uri = ContentUris.withAppendedId(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id);
                        PendingIntent request = MediaStore.createDeleteRequest(getContentResolver(),
                                Collections.singletonList(uri));
                        startIntentSenderForResult(request.getIntentSender(), REQUEST_DELETE_AUDIO,
                                null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException | RuntimeException failure) {
                        new AlertDialog.Builder(this)
                                .setMessage("Couldn't request deletion. Please use your file manager instead.")
                                .setPositiveButton("OK", null).show();
                    }
                }).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DELETE_AUDIO && resultCode == RESULT_OK) {
            // Android completes deletion before RESULT_OK. The activity may be
            // unbound after the system confirmation screen; defer a rescan if so.
            if (service != null) service.scanMusic();
            else rescanAfterConfirmedDelete = true;
        }
    }

    private void drawClock() {
        if (service == null) return;
        int current = service.positionMs();
        int length = Math.max(0, service.durationMs());
        if (!touchingSeek) {
            seek.setMax(Math.max(1,length));
            seek.setProgress(Math.min(current,length));
        }
        elapsed.setText(time(current));
        duration.setText(time(length));
        Song selected = service.currentSong();
        turntable.setPlayback(service.isPlaying(), current, length, selected == null ? -1L : selected.id);
    }

    private String time(int ms) {
        int seconds = Math.max(0,ms) / 1000;
        return String.format(Locale.US,"%d:%02d",seconds/60,seconds%60);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
