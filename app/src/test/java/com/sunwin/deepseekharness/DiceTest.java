package com.sunwin.deepseekharness;

import org.junit.Test;
import static org.junit.Assert.*;

public class DiceTest {
    @Test public void parsesModifiersAndLimitsBeforeDrawing() {
        Dice d=Dice.roll(" 3D10 - 2 ");
        assertEquals("3d10-2",d.formula); assertEquals(3,d.rolls.size());
        assertEquals(d.rolls.get(0)+d.rolls.get(1)+d.rolls.get(2)-2,d.total);
        assertEquals("[骰子 3d10-2 | "+d.rolls.get(0)+","+d.rolls.get(1)+","+d.rolls.get(2)+" | "+d.total+"]",d.block());
        assertEquals(100,Dice.roll("100d4+99999").rolls.size());
        assertEquals("d6",Dice.roll("1d6-0").formula);
        for (String expr:new String[]{"","d3","d7","0d20","101d4","-d6","d6+-2","d6+","7","d100+100000","2d6;d8","no","9999999999d4"})
            assertThrows(expr,IllegalArgumentException.class,()->Dice.roll(expr));
    }
    @Test public void everyFaceIsReachableAndSixSidedCountsStayWithinBroadBounds() {
        for (int faces:Dice.FACES) {
            int[] counts=new int[faces+1];
            for (int i=0;i<300;i++) {
                Dice batch=Dice.roll("100d"+faces+"+3"); int sum=3;
                for (int die:batch.rolls) {
                    assertTrue(die>=1 && die<=faces); counts[die]++; sum+=die;
                }
                assertEquals(sum,batch.total);
            }
            for (int i=1;i<=faces;i++) {
                assertTrue(counts[i]>0);
                if (faces==6) assertTrue(counts[i]>4300 && counts[i]<5700);
            }
        }
    }
}
