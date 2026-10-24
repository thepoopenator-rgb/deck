import com.fulldeck.player.ShuffleDeck;
import com.fulldeck.player.Song;
import java.util.*;

public final class ShuffleDeckTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        List<Song> library = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) library.add(new Song(i,"Track " + i, "Artist " + (i % 80), 180000));
        ShuffleDeck deck = new ShuffleDeck();
        deck.newCycle(library, -1);
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            require(seen.add(deck.currentId()), "Repeat before complete: " + deck.currentId());
            require(deck.played() == i + 1, "Wrong played count");
            if (i < 999) require(deck.advance(library), "Should have next song");
        }
        require(seen.size() == 1000 && deck.left() == 0, "Incomplete library");
        long last = deck.currentId();
        deck.advance(library);
        require(deck.played() == 1 && deck.currentId() != last, "New cycle must avoid adjacent repeat");

        // Restore order without playing a previously heard song again.
        List<Long> order = deck.snapshot();
        for (int i = 0; i < 200; i++) deck.advance(library);
        long midId = deck.currentId();
        List<Long> old = deck.snapshot();
        int index = deck.index();
        ShuffleDeck restart = new ShuffleDeck();
        restart.restore(library, old, index);
        require(restart.currentId() == midId && restart.index() == index, "Did not restore session");
        Set<Long> alreadyPlayed = new HashSet<>(old.subList(0,index + 1));
        while (restart.left() > 0) {
            restart.advance(library);
            require(!alreadyPlayed.contains(restart.currentId()), "Repeat after restoration");
            alreadyPlayed.add(restart.currentId());
        }
        require(alreadyPlayed.size() == 1000, "Full restored cycle did not finish");

        // New tracks are inserted into unplayed space without resetting history.
        deck.newCycle(library,-1);
        for (int i = 0; i < 50; i++) deck.advance(library);
        long playing = deck.currentId();
        library.add(new Song(999999,"New song","New artist",123000));
        ShuffleDeck updated = new ShuffleDeck();
        updated.restore(library, deck.snapshot(),deck.index());
        require(updated.currentId() == playing, "Refresh lost current song");
        require(updated.total() == 1001 && updated.snapshot().contains(999999L),"New song missing");
        System.out.println("PASS: 1000-song shuffle, cycle transition, restart, refresh/new tracks");
    }
}
