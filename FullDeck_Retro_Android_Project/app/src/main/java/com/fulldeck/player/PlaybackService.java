package com.fulldeck.player;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground local-audio player with MediaStyle lock-screen controls. */
public final class PlaybackService extends Service {
    public static final String ACTION_PLAY = "com.fulldeck.player.PLAY";
    public static final String ACTION_PAUSE = "com.fulldeck.player.PAUSE";
    public static final String ACTION_TOGGLE = "com.fulldeck.player.TOGGLE";
    public static final String ACTION_NEXT = "com.fulldeck.player.NEXT";
    public static final String ACTION_RANDOM = "com.fulldeck.player.RANDOM";
    public static final String ACTION_PREV = "com.fulldeck.player.PREV";
    public static final String ACTION_STOP = "com.fulldeck.player.STOP";
    private static final String CHANNEL = "full_deck_playback";
    private static final int NOTIFICATION_ID = 8142;
    private static final int MAX_CONSECUTIVE_ERRORS = 6;

    public interface Listener { void onPlayerChanged(); }
    public final class LocalBinder extends Binder { public PlaybackService getService() { return PlaybackService.this; } }
    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayList<Song> library = new ArrayList<>();
    private final Map<Long,Song> byId = new HashMap<>();
    private final ShuffleDeck deck = new ShuffleDeck();
    // One worker for MediaStore queries; no 20,000-row cursor work on the UI thread.
    private final ExecutorService scanWorker = Executors.newSingleThreadExecutor();
    private final Set<Long> excludedForThisDeck = new HashSet<>();
    private boolean scanning;
    private boolean scanAgain;
    private boolean destroyed;
    private final Runnable updateTicker = new Runnable() {
        @Override public void run() {
            if (player != null && ready && player.isPlaying()) {
                elapsedSeconds++;
                if (elapsedSeconds % 8 == 0) savePosition();
                notifyListener();
            }
            mainHandler.postDelayed(this, 1000);
        }
    };

    private Listener listener;
    private MediaPlayer player;
    private MediaSession session;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean loaded;
    private boolean ready;
    private boolean startWhenPrepared;
    private boolean playing;
    private boolean foreground;
    private int resumeFromMs;
    private long resumeId = -1;
    private int elapsedSeconds;
    private int failuresInARow;
    private String lastError = "";

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        AudioManager.OnAudioFocusChangeListener focusListener = change -> {
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                pause();
            } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK && player != null) {
                player.setVolume(0.25f, 0.25f);
            } else if (change == AudioManager.AUDIOFOCUS_GAIN && player != null) {
                player.setVolume(1f,1f);
            }
        };
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(focusListener)
                .build();
        session = new MediaSession(this, "Full Deck");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { command(ACTION_PLAY); }
            @Override public void onPause() { command(ACTION_PAUSE); }
            @Override public void onSkipToNext() { command(ACTION_NEXT); }
            @Override public void onSkipToPrevious() { command(ACTION_PREV); }
            @Override public void onStop() { command(ACTION_STOP); }
        });
        session.setActive(true);
        updateSession();
        mainHandler.post(updateTicker);
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // Android requires foreground promotion promptly after startForegroundService().
        promote();
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            pause();
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!loaded) scanMusic();
        if (ACTION_PLAY.equals(action)) play();
        else if (ACTION_PAUSE.equals(action)) pause();
        else if (ACTION_TOGGLE.equals(action)) { if (playing) pause(); else play(); }
        else if (ACTION_NEXT.equals(action)) skipNext();
        else if (ACTION_RANDOM.equals(action)) randomNext();
        else if (ACTION_PREV.equals(action)) skipPrevious();
        updateNotification();
        return START_NOT_STICKY;
    }

    private void command(String action) {
        Intent request = new Intent(this, PlaybackService.class).setAction(action);
        if (ACTION_PAUSE.equals(action) || ACTION_STOP.equals(action)) startService(request);
        else startForegroundService(request);
    }

    public void setListener(Listener value) { listener = value; notifyListener(); }
    public void clearListener(Listener value) { if (listener == value) listener = null; }
    public List<Song> songs() { return new ArrayList<>(library); }
    public Song currentSong() { return byId.get(deck.currentId()); }
    public List<Song> upNext(int limit) {
        ArrayList<Song> songs = new ArrayList<>();
        for (Long id : deck.upcoming(limit)) { Song song = byId.get(id); if (song != null) songs.add(song); }
        return songs;
    }
    public int libraryCount() { return library.size(); }
    public boolean hasScanned() { return loaded; }
    public boolean isScanning() { return scanning; }
    public int playedCount() { return deck.played(); }
    public int remainingCount() { return deck.left(); }
    public int totalCount() { return deck.total(); }
    public boolean isPlaying() { return playing; }
    public String error() { return lastError; }
    public int positionMs() {
        try { if (player != null && ready) return player.getCurrentPosition(); }
        catch (IllegalStateException ignored) { }
        return resumeId == deck.currentId() ? resumeFromMs : 0;
    }
    public int durationMs() {
        Song song = currentSong();
        return song == null ? 0 : (int) Math.min(Integer.MAX_VALUE, song.durationMs);
    }
    public void seekTo(int ms) {
        if (player != null && ready) {
            player.seekTo(Math.max(0,Math.min(ms,durationMs())));
            savePosition(); notifyListener();
        }
    }

    public boolean hasAudioPermission() {
        String permission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** Reads every indexed audio file across the phone's shared music storage, including
     * subfolders, via MediaStore. A background worker keeps the player responsive. */
    public void scanMusic() {
        if (!hasAudioPermission()) {
            lastError = "Allow music access to find your MP3s.";
            notifyListener();
            return;
        }
        if (scanning) { scanAgain = true; return; }
        scanning = true;
        lastError = "Scanning music folders…";
        notifyListener();
        scanWorker.execute(() -> {
            ArrayList<Song> found = new ArrayList<>();
            String problem = "";
            ArrayList<String> fields = new ArrayList<>();
            fields.add(MediaStore.Audio.Media._ID);
            fields.add(MediaStore.Audio.Media.TITLE);
            fields.add(MediaStore.Audio.Media.ARTIST);
            fields.add(MediaStore.Audio.Media.DURATION);
            fields.add(MediaStore.Audio.Media.DISPLAY_NAME);
            if (Build.VERSION.SDK_INT >= 29) fields.add(MediaStore.Audio.Media.RELATIVE_PATH);
            String selection = "(" + MediaStore.Audio.Media.IS_MUSIC + " != 0 OR " +
                    MediaStore.Audio.Media.MIME_TYPE + " = ? OR " +
                    MediaStore.Audio.Media.DISPLAY_NAME + " LIKE ?) AND " +
                    MediaStore.Audio.Media.DURATION + " >= ?";
            String[] args = { "audio/mpeg", "%.mp3", "0" };
            try (Cursor cursor = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    fields.toArray(new String[0]), selection, args,
                    MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
                if (cursor != null) {
                    int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                    int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                    int artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                    int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                    int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
                    int folderColumn = Build.VERSION.SDK_INT >= 29 ?
                            cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH) : -1;
                    while (cursor.moveToNext()) {
                        String title = cursor.getString(titleColumn);
                        if (title == null || title.trim().isEmpty()) title = cursor.getString(nameColumn);
                        String folder = folderColumn < 0 ? "" : cursor.getString(folderColumn);
                        found.add(new Song(cursor.getLong(idColumn), title,
                                cursor.getString(artistColumn), cursor.getLong(durationColumn), folder));
                    }
                }
            } catch (Exception error) {
                problem = "Couldn't scan music: " + error.getClass().getSimpleName();
            }
            final String scanError = problem;
            mainHandler.post(() -> {
                if (destroyed) return;
                scanning = false;
                if (scanError.isEmpty()) applyScan(found);
                else { lastError = scanError; notifyListener(); }
                if (scanAgain) {
                    scanAgain = false;
                    scanMusic();
                }
            });
        });
    }

    private void applyScan(List<Song> found) {
        long previousId = deck.currentId();
        List<Long> previousOrder;
        int previousIndex;
        if (loaded) {
            previousOrder = deck.snapshot();
            previousIndex = deck.index();
        } else {
            SharedPreferences prefs = getSharedPreferences("full_deck_state", MODE_PRIVATE);
            previousOrder = loadIds(prefs.getString("deck", "[]"));
            previousIndex = prefs.getInt("position", -1);
            resumeFromMs = prefs.getInt("time", 0);
            resumeId = prefs.getLong("timeForId", -1);
            excludedForThisDeck.addAll(loadIds(prefs.getString("excluded", "[]")));
        }
        library.clear();
        byId.clear();
        for (Song song : found) {
            if (!byId.containsKey(song.id)) {
                library.add(song);
                byId.put(song.id, song);
            }
        }
        excludedForThisDeck.retainAll(byId.keySet());
        ArrayList<Song> eligible = new ArrayList<>();
        for (Song song : library) if (!excludedForThisDeck.contains(song.id)) eligible.add(song);
        deck.restore(eligible, previousOrder, previousIndex);
        loaded = true;
        lastError = library.isEmpty() ?
                "No indexed music found. Files in regular music subfolders are included automatically." : "";
        if (previousId != -1 && previousId != deck.currentId() && player != null) {
            boolean continuePlaying = playing || startWhenPrepared;
            releasePlayer();
            if (continuePlaying && deck.currentId() != -1) loadCurrent(true);
        }
        saveDeck();
        updateSession();
        updateNotification();
        notifyListener();
    }

    public void startNewDeck() {
        long old = deck.currentId();
        excludedForThisDeck.clear();
        deck.newCycle(library, old);
        resumeFromMs = 0;
        resumeId = -1;
        releasePlayer();
        saveDeck();
        updateSession();
        notifyListener();
    }

    /** No-repeat random jump: choose only from songs still waiting this cycle. */
    public void randomNext() {
        if (library.isEmpty()) return;
        boolean autoplay = playing || startWhenPrepared;
        int before = deck.index();
        if (deck.randomAdvance(library)) {
            if (deck.index() <= before) excludedForThisDeck.clear(); // new cycle
            resumeFromMs = 0;
            resumeId = -1;
            saveDeck(); // random swap changes queue order
            loadCurrent(autoplay);
            stateChanged();
        }
    }

    /** Keep MP3 on device; omit it from the remaining queue until the next fresh deck. */
    public void removeCurrentFromDeck() {
        long id = deck.currentId();
        if (id == -1) return;
        boolean autoplay = playing || startWhenPrepared;
        int before = deck.index();
        excludedForThisDeck.add(id);
        deck.remove(id);
        if (deck.total() == 0 || before >= deck.total()) {
            // Round is complete. Start next round from the full library.
            excludedForThisDeck.clear();
            deck.newCycle(library, id);
        }
        resumeFromMs = 0;
        resumeId = -1;
        saveDeck();
        if (deck.currentId() != -1) loadCurrent(autoplay);
        else releasePlayer();
        stateChanged();
    }

    private List<Long> loadIds(String raw) {
        ArrayList<Long> ids = new ArrayList<>();
        try { JSONArray array = new JSONArray(raw); for (int i = 0; i < array.length(); i++) ids.add(array.getLong(i)); }
        catch (Exception ignored) { }
        return ids;
    }

    /** Queue order changes only on scan, random jump, or new deck, not every second. */
    private void saveDeck() {
        JSONArray array = new JSONArray();
        for (Long id : deck.snapshot()) array.put(id);
        JSONArray excluded = new JSONArray();
        for (Long id : excludedForThisDeck) excluded.put(id);
        getSharedPreferences("full_deck_state", MODE_PRIVATE).edit()
                .putString("deck", array.toString())
                .putString("excluded", excluded.toString())
                .apply();
        savePosition();
    }

    /** Saves the index and listening position without rewriting 20,000 IDs. */
    private void savePosition() {
        getSharedPreferences("full_deck_state", MODE_PRIVATE).edit()
                .putInt("position", deck.index()).putLong("timeForId", deck.currentId())
                .putInt("time", positionMs()).apply();
    }

    private void play() {
        if (deck.currentId() == -1) { lastError = "No music found yet. Tap SCAN MUSIC."; notifyListener(); return; }
        if (player != null && ready) {
            if (audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                player.start(); playing = true;
            } else { lastError = "Another app is using audio."; }
            stateChanged();
            return;
        }
        loadCurrent(true);
    }

    private void loadCurrent(boolean autoplay) {
        releasePlayer();
        Song song = currentSong();
        if (song == null) { lastError = "Song is unavailable."; stateChanged(); return; }
        startWhenPrepared = autoplay;
        MediaPlayer next = new MediaPlayer();
        player = next;
        try {
            next.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build());
            Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id);
            next.setDataSource(this, uri);
            next.setOnPreparedListener(prepared -> {
                if (player != prepared) return;
                ready = true;
                if (resumeId == song.id && resumeFromMs > 0) {
                    int target = Math.min(resumeFromMs, Math.max(0, durationMs() - 1000));
                    prepared.seekTo(target);
                }
                resumeFromMs = 0;
                resumeId = -1;
                if (startWhenPrepared && audioManager.requestAudioFocus(focusRequest)
                        == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    prepared.start(); playing = true;
                    failuresInARow = 0;
                }
                savePosition(); stateChanged();
            });
            next.setOnCompletionListener(ended -> {
                if (player == ended) skipNext();
            });
            next.setOnErrorListener((failed, what, extra) -> {
                if (player != failed) return true;
                lastError = "One track couldn't play; moving to the next.";
                failuresInARow++;
                if (failuresInARow < MAX_CONSECUTIVE_ERRORS) {
                    mainHandler.post(this::skipNext);
                } else {
                    releasePlayer(); stateChanged();
                }
                return true;
            });
            next.prepareAsync();
            updateSession(); notifyListener();
        } catch (IOException | SecurityException | IllegalStateException error) {
            lastError = "Couldn't open " + song.title + ". Skipping it.";
            failuresInARow++;
            if (failuresInARow < MAX_CONSECUTIVE_ERRORS) mainHandler.post(this::skipNext);
            else { releasePlayer(); stateChanged(); }
        }
    }

    private void skipNext() {
        if (library.isEmpty()) return;
        boolean autoplay = playing || startWhenPrepared;
        deck.advance(library);
        resumeFromMs = 0;
        resumeId = -1;
        if (deck.index() == 0) { excludedForThisDeck.clear(); saveDeck(); }
        else savePosition();
        loadCurrent(autoplay);
        stateChanged();
    }

    private void skipPrevious() {
        if (library.isEmpty()) return;
        if (positionMs() > 3500) { seekTo(0); return; }
        boolean autoplay = playing || startWhenPrepared;
        if (!deck.back()) { seekTo(0); return; }
        resumeFromMs = 0;
        resumeId = -1;
        savePosition();
        loadCurrent(autoplay);
        stateChanged();
    }

    private void pause() {
        startWhenPrepared = false;
        if (player != null && ready && player.isPlaying()) player.pause();
        playing = false;
        savePosition();
        stateChanged();
    }

    private void releasePlayer() {
        ready = false;
        playing = false;
        startWhenPrepared = false;
        if (player != null) {
            try { player.reset(); player.release(); } catch (Exception ignored) { }
            player = null;
        }
    }

    private void stateChanged() {
        updateSession(); updateNotification(); notifyListener();
    }

    private void notifyListener() {
        if (listener != null) listener.onPlayerChanged();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Music playback",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Player controls while music is playing");
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    private void promote() {
        if (!foreground) {
            startForeground(NOTIFICATION_ID, buildNotification());
            foreground = true;
        }
    }

    private PendingIntent actionPendingIntent(String action, int code) {
        Intent intent = new Intent(this, PlaybackService.class).setAction(action);
        return PendingIntent.getService(this, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification() {
        Song song = currentSong();
        Intent launchIntent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 17, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_music)
                .setContentTitle(song == null ? "Full Deck" : song.title)
                .setContentText(song == null ? "No-repeat music shuffle" : song.artist)
                .setContentIntent(contentIntent)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(playing)
                .addAction(android.R.drawable.ic_media_previous, "Previous", actionPendingIntent(ACTION_PREV, 1))
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pause" : "Play", actionPendingIntent(ACTION_TOGGLE, 2))
                .addAction(android.R.drawable.ic_media_next, "Next", actionPendingIntent(ACTION_NEXT, 3))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", actionPendingIntent(ACTION_STOP, 4));
        if (session != null) builder.setStyle(new Notification.MediaStyle()
                .setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0,1,2));
        return builder.build();
    }

    private void updateNotification() {
        if (foreground) ((NotificationManager)getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_ID, buildNotification());
    }

    private void updateSession() {
        if (session == null) return;
        Song song = currentSong();
        if (song != null) {
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, song.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, song.artist)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, song.durationMs).build());
        }
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        positionMs(), playing ? 1f : 0f)
                .build());
    }

    @Override public void onDestroy() {
        destroyed = true;
        savePosition();
        scanWorker.shutdownNow();
        mainHandler.removeCallbacks(updateTicker);
        releasePlayer();
        if (session != null) session.release();
        if (audioManager != null) audioManager.abandonAudioFocusRequest(focusRequest);
        super.onDestroy();
    }
}
