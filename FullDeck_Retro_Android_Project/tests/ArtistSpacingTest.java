import com.fulldeck.player.ShuffleDeck;
import com.fulldeck.player.Song;
import java.util.*;
public final class ArtistSpacingTest {
 public static void main(String[] args) {
  ArrayList<Song> songs=new ArrayList<>();
  for(int i=0;i<20000;i++)songs.add(new Song(i+1,"Track "+i,"Artist "+(i%200),120000));
  ShuffleDeck deck=new ShuffleDeck();deck.newCycle(songs,-1);
  Map<Long,String> artist=new HashMap<>();for(Song s:songs)artist.put(s.id,s.artist);
  List<Long> ids=deck.snapshot();
  int near=0;
  for(int i=1;i<ids.size();i++)for(int j=Math.max(0,i-3);j<i;j++)
    if(artist.get(ids.get(i)).equals(artist.get(ids.get(j))))near++;
  if(near>0)throw new AssertionError("Found "+near+" artists repeated within 3 songs");
  System.out.println("PASS: 20,000 tracks spread across 200 artists with no adjacent 3-song artist repeats");
 }
}
