package com.fulldeck.player;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

/**
 * A once-through shuffle queue. All tracks get exactly one turn per cycle;
 * it does NOT choose a random song independently at each transition.
 * Manual Previous/New Deck overrides the once-through promise intentionally.
 * No Android dependencies, so this logic can be tested with plain Java.
 */
public final class ShuffleDeck {
    private final SecureRandom random = new SecureRandom();
    private final ArrayList<Long> order = new ArrayList<>();
    private final Map<Long,String> artistKeys = new HashMap<>();
    private int position = -1;

    public void newCycle(List<Song> library, long avoidFirst) {
        order.clear();
        rememberArtists(library);
        Set<Long> seen = new HashSet<>();
        for (Song s : library) if (seen.add(s.id)) order.add(s.id);
        shuffle(order);
        spreadArtists(order,0);
        if (order.size() > 1 && order.get(0) == avoidFirst) {
            int replacement = 1 + random.nextInt(order.size() - 1);
            Collections.swap(order, 0, replacement);
        }
        position = order.isEmpty() ? -1 : 0;
    }

    /** Restore progress, removing deleted tracks and inserting newly discovered ones in the unplayed suffix. */
    public void restore(List<Song> library, List<Long> savedOrder, int savedPosition) {
        rememberArtists(library);
        if (savedOrder == null || savedOrder.isEmpty()) {
            newCycle(library, -1);
            return;
        }
        Set<Long> available = new HashSet<>();
        for (Song s : library) available.add(s.id);
        Set<Long> seen = new HashSet<>();
        ArrayList<Long> prefix = new ArrayList<>();
        ArrayList<Long> suffix = new ArrayList<>();
        long savedCurrent = savedPosition >= 0 && savedPosition < savedOrder.size()
                ? savedOrder.get(savedPosition) : -1;
        boolean currentSurvives = available.contains(savedCurrent);
        for (int i = 0; i < savedOrder.size(); i++) {
            long id = savedOrder.get(i);
            if (!available.contains(id) || !seen.add(id)) continue;
            if (i < savedPosition) prefix.add(id);
            else if (i == savedPosition && currentSurvives) prefix.add(id);
            else suffix.add(id);
        }
        ArrayList<Long> newcomers = new ArrayList<>();
        for (Song song : library) if (seen.add(song.id)) newcomers.add(song.id);
        shuffle(newcomers);
        spreadArtists(newcomers,0);
        // Linear-time random merge preserving existing unplayed order, instead
        // of O(n²) ArrayList insertions when a large folder is added.
        ArrayList<Long> merged = new ArrayList<>(suffix.size() + newcomers.size());
        int existing = 0, fresh = 0;
        while (existing < suffix.size() || fresh < newcomers.size()) {
            int oldLeft = suffix.size() - existing;
            int newLeft = newcomers.size() - fresh;
            if (newLeft > 0 && (oldLeft == 0 || random.nextInt(oldLeft + newLeft) < newLeft)) {
                merged.add(newcomers.get(fresh++));
            } else {
                merged.add(suffix.get(existing++));
            }
        }
        suffix = merged;
        if (!currentSurvives && suffix.isEmpty() && !prefix.isEmpty()) {
            // If the missing track was the very last unplayed item, the round
            // has ended; starting a new round is safer than replaying a heard song.
            newCycle(library, prefix.get(prefix.size() - 1));
            return;
        }

        order.clear();
        order.addAll(prefix);
        order.addAll(suffix);
        if (order.isEmpty()) position = -1;
        else if (currentSurvives) position = Math.max(0, prefix.size() - 1);
        else position = Math.min(prefix.size(), order.size() - 1);
    }

    public boolean advance(List<Song> library) {
        if (order.isEmpty()) { newCycle(library, -1); return position != -1; }
        if (position + 1 < order.size()) { position++; return true; }
        long last = currentId();
        newCycle(library, last);
        return position != -1;
    }

    /** Pick uniformly among UNPLAYED songs, moving the selected track to the next slot.
     * Unlike picking at random from the library, this cannot cause an early repeat. */
    public boolean randomAdvance(List<Song> library) {
        if (order.isEmpty() || position + 1 >= order.size()) return advance(library);
        int next = position + 1;
        int candidate = next + random.nextInt(order.size() - next);
        // Try several random candidates to avoid immediate artist repeats.
        // Falls back to any unheard track when avoiding the artist is impossible.
        for (int attempt=0; attempt<14 && nearSameArtist(order,candidate,next,3); attempt++) {
            candidate = next + random.nextInt(order.size() - next);
        }
        if (nearSameArtist(order,candidate,next,3)) {
            for (int i=next;i<Math.min(order.size(),next+128);i++) {
                if (!nearSameArtist(order,i,next,3)) { candidate=i; break; }
            }
        }
        Collections.swap(order, next, candidate);
        position = next;
        return true;
    }

    /** Remove a track for this round, preserving already-played progress. */
    public boolean remove(long id) {
        int idx = order.indexOf(id);
        if (idx == -1) return false;
        order.remove(idx);
        if (order.isEmpty()) position = -1;
        else if (idx < position) position--;
        else if (idx == position) position = Math.min(idx, order.size() - 1);
        return true;
    }

    public boolean back() {
        if (position <= 0) return false;
        position--;
        return true;
    }

    public long currentId() {
        return position >= 0 && position < order.size() ? order.get(position) : -1;
    }

    public int played() { return position < 0 ? 0 : position + 1; }
    public int total() { return order.size(); }
    public int left() { return Math.max(0, total() - played()); }
    public int index() { return position; }
    public List<Long> snapshot() { return new ArrayList<>(order); }

    public List<Long> upcoming(int count) {
        ArrayList<Long> result = new ArrayList<>();
        for (int i = position + 1; i < order.size() && result.size() < count; i++) result.add(order.get(i));
        return result;
    }

    /** Artist diversity is a preference, not a second source of repeats.
     * Preserve a valid no-repeat permutation even if the library contains
     * only one artist or too many songs by one artist. */
    private void rememberArtists(List<Song> library) {
        artistKeys.clear();
        for (Song song : library) {
            String artist=song.artist.trim().toLowerCase(java.util.Locale.ROOT);
            // Unknown tags are individual files, not one giant artificial artist.
            if (artist.equals("unknown artist")) artist="__unknown_"+song.id;
            artistKeys.put(song.id,artist);
        }
    }

    private boolean nearSameArtist(List<Long> list,int candidate,int place,int distance) {
        String key=artistKeys.get(list.get(candidate));
        if (key==null) return false;
        for (int previous=Math.max(0,place-distance);previous<place;previous++) {
            if (key.equals(artistKeys.get(list.get(previous)))) return true;
        }
        return false;
    }

    private void spreadArtists(ArrayList<Long> ids,int start) {
        // One pass with bounded lookahead: O(n) work for normal diverse libraries.
        for (int i=start;i<ids.size();i++) {
            if (!nearSameArtist(ids,i,i,3)) continue;
            int limit=Math.min(ids.size(),i+160);
            for (int j=i+1;j<limit;j++) {
                if (!nearSameArtist(ids,j,i,3)) {
                    Collections.swap(ids,i,j);
                    break;
                }
            }
        }
    }

    private void shuffle(ArrayList<Long> list) {
        for (int i = list.size() - 1; i > 0; i--) {
            Collections.swap(list, i, random.nextInt(i + 1));
        }
    }
}
