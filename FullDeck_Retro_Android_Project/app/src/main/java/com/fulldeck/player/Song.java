package com.fulldeck.player;

/** Metadata only: tracks remain on the user's device. */
public final class Song {
    public final long id;
    public final String title;
    public final String artist;
    public final long durationMs;
    public final String folder;

    public Song(long id, String title, String artist, long durationMs) {
        this(id, title, artist, durationMs, "");
    }

    public Song(long id, String title, String artist, long durationMs, String folder) {
        this.id = id;
        this.title = (title == null || title.trim().isEmpty()) ? "Unknown title" : title;
        this.artist = (artist == null || artist.trim().isEmpty() || "<unknown>".equalsIgnoreCase(artist))
                ? "Unknown artist" : artist;
        this.durationMs = Math.max(0, durationMs);
        this.folder = folder == null ? "" : folder;
    }
}
