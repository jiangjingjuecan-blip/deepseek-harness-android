package com.sunwin.deepseekharness;

import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class TypewriterTest {
    private static String repeat(String text,int n) { return String.join("",Collections.nCopies(n,text)); }
    @Test public void shortChineseReplyAdvancesAtEightCharactersPerSecond() {
        Typewriter writer=new Typewriter(8);
        String body=repeat("春江",12);
        writer.receive(body,0);
        for (int t=0;t<=1000;t+=10) writer.advance(t);
        assertEquals(8,writer.length());
        for (int t=1010;t<=2000;t+=10) writer.advance(t);
        assertEquals(16,writer.length());
    }
    @Test public void aBurstAndEachContinuingPrefixStayWithinFourSeconds() {
        Typewriter writer=new Typewriter(8);
        String block=repeat("水",200),body="";
        for (int t=0;t<=8000;t+=20) {
            if (t%1000==0 && t<5000) {
                body+=block;
                writer.receive(body,t);
                assertTrue(writer.length()<body.length());
            }
            writer.advance(t);
            if (t>=4000) assertTrue(writer.length()>=(1+(t-4000)/1000)*200);
        }
        assertEquals(body.length(),writer.length());
    }
    @Test public void completedBurstWindsDownAndInstantAndTapStickToFutureDeltas() {
        String text=repeat("雪",1200);
        Typewriter tail=new Typewriter(8);
        tail.receive(text,1000); tail.end(1000);
        tail.advance(1016); assertTrue(tail.length()<text.length());
        for (int t=1032;t<=1650;t+=16) tail.advance(t);
        assertEquals(text.length(),tail.length());
        Typewriter instant=new Typewriter(0); instant.receive(text,0);
        assertEquals(text.length(),instant.length());
        Typewriter tap=new Typewriter(8); tap.receive(text,0); tap.finish();
        assertEquals(text.length(),tap.length());
        tap.receive(text+"！",1); assertEquals(text.length()+1,tap.length());
    }
    @Test public void aReplacementFrameAndSupplementaryCharactersKeepValidPrefixes() {
        Typewriter writer=new Typewriter(8);
        String text=repeat("𠮷",12);
        writer.receive(text,100);
        for (int t=100;t<=1100;t+=10) {
            writer.advance(t);
            assertEquals(0,writer.length()%2);
        }
        writer.receive("新答",1110); writer.end(1110);
        writer.advance(1800);
        assertEquals(2,writer.length());
    }
}
