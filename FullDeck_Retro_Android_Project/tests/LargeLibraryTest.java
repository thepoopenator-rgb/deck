import com.fulldeck.player.ShuffleDeck;
import com.fulldeck.player.Song;
import java.util.*;

public final class LargeLibraryTest {
    private static void check(boolean b, String msg) { if (!b) throw new AssertionError(msg); }
    public static void main(String[] args) {
        final int n = 20_000;
        List<Song> library = new ArrayList<>();
        for (int i=0;i<n;i++) {
            library.add(new Song(i+1, "Song " + i, "Artist " + (i%1500), 120000L,
                    "Music/Collection/Folder" + (i % 80) + "/Subfolder/"));
        }
        ShuffleDeck deck = new ShuffleDeck();
        long started = System.nanoTime();
        deck.newCycle(library, -1);
        check(deck.total()==n, "Some folders missing");
        HashSet<Long> seen = new HashSet<>();
        for (int i=0;i<n;i++) {
            long id = deck.currentId();
            check(seen.add(id), "Repeat before 20,000 songs complete: " + id);
            if (i % 3 == 0) {
                if (i < n-1) deck.randomAdvance(library);
            } else if (i < n-1) deck.advance(library);
            if (i==9000) {
                List<Long> snapshot = deck.snapshot();
                int pos = deck.index();
                ShuffleDeck restart = new ShuffleDeck();
                restart.restore(library, snapshot, pos);
                check(restart.currentId() == deck.currentId(), "Bad restart");
                deck = restart;
            }
        }
        check(seen.size()==n, "Expected no repeats in 20,000-song deck");
        check(deck.left()==0, "Incorrect songs remaining");
        long prev = deck.currentId();
        deck.advance(library);
        check(deck.currentId()!=prev && deck.total()==n, "New cycle boundary repeat");
        long removable = deck.currentId();
        check(deck.remove(removable), "Failed remove from deck");
        check(deck.total()==n-1 && !deck.snapshot().contains(removable), "Track still in deck");

        // Adding an entire 20,000-song folder to a 20,000-song library must
        // remain linear time, and existing unplayed order must remain intact.
        List<Long> before = deck.snapshot();
        List<Long> unplayed = new ArrayList<>(before.subList(deck.index()+1, before.size()));
        for (int i=n;i<2*n;i++) library.add(new Song(i+1,"Song " + i,"Artist",200000));
        ShuffleDeck refresh = new ShuffleDeck();
        refresh.restore(library, deck.snapshot(), deck.index());
        check(refresh.total()==2*n, "Missing imported large folder");
        Set<Long> unplayedSet = new HashSet<>(unplayed);
        List<Long> afterUnplayedOld = new ArrayList<>();
        for (long id: refresh.snapshot()) if (unplayedSet.contains(id)) afterUnplayedOld.add(id);
        check(afterUnplayedOld.equals(unplayed), "Changed previous unplayed order on refresh");
        System.out.println("PASS: 20,000 tracks (random without repeats, nested folders, restart, removal); 20,000 new songs merged. " +
                "Elapsed " + ((System.nanoTime()-started)/1_000_000) + " ms");
    }
}
