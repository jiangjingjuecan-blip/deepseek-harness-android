package com.sunwin.deepseekharness;

import java.util.ArrayDeque;

/** UI-thread clock. Offsets are UTF-16 boundaries; rate and credit use reading units. */
final class Typewriter {
    static final int DEFAULT_SPEED=8;
    static final String PREF="reveal_speed";
    private static final long MAX_LAG=4000, WIND_DOWN=420;
    private static final class Batch {
        final int end;
        final long due;
        final double units;
        Batch(int end,double units,long due) { this.end=end; this.units=units; this.due=due; }
    }
    private final ArrayDeque<Batch> batches=new ArrayDeque<>();
    private final int speed;
    private String text="";
    private int length,revision;
    private double received,shown,credit,rate;
    private long last,endAt=Long.MAX_VALUE;
    private boolean instant;
    Typewriter(int speed) { this.speed=speed; rate=speed; instant=speed==0; }
    int revision() { return revision; }
    int length() { return length; }
    boolean moving() { return length<text.length(); }
    String text() { return text; }
    void receive(String next,long now) {
        if (next==text || next.equals(text)) return;
        if (!moving()) { last=now; credit=0; rate=speed; }
        int shared=text.length();
        if (!next.startsWith(text)) {
            revision++; shared=0;
            while (shared<text.length() && shared<next.length() && text.charAt(shared)==next.charAt(shared)) shared++;
            if (shared>0 && Character.isHighSurrogate(next.charAt(shared-1))) shared--;
            length=Math.min(length,shared);
            batches.clear(); credit=0;
            received=units(next,0,shared); shown=units(next,0,length);
        }
        received+=units(next,shared,next.length()); text=next;
        if (instant) { finish(); return; }
        if (moving()) batches.addLast(new Batch(text.length(),received,now+MAX_LAG));
    }
    void end(long now) { if (endAt==Long.MAX_VALUE) endAt=now+WIND_DOWN; }
    void finish() { instant=true; length=text.length(); shown=received; batches.clear(); }
    void advance(long now) {
        if (!moving() || now<=last) return;
        long elapsed=now-last;
        Batch first=batches.peekFirst();
        double backlog=received-shown;
        double target=Math.max(speed,backlog/3.0);
        if (first!=null) target=Math.max(target,(first.units-shown)*1000/Math.max(1,first.due-last));
        rate+=(target-rate)*(1-Math.exp(-elapsed/180.0));
        if (endAt!=Long.MAX_VALUE) rate=Math.max(rate,backlog*1000/Math.max(1,endAt-last));
        credit+=rate*elapsed/1000; last=now;
        int due=now>=endAt ? text.length() : length;
        while (!batches.isEmpty() && batches.peekFirst().due<=now) due=Math.max(due,batches.removeFirst().end);
        while (length<text.length()) {
            int cp=text.codePointAt(length);
            double cost=cost(cp);
            if (length>=due && credit+1e-7<cost) break;
            credit=Math.max(0,credit-cost); shown+=cost; length+=Character.charCount(cp);
        }
        while (!batches.isEmpty() && batches.peekFirst().end<=length) batches.removeFirst();
    }
    private static double units(String value,int from,int end) {
        double total=0;
        for (int i=from;i<end;) {
            int cp=value.codePointAt(i); total+=cost(cp); i+=Character.charCount(cp);
        }
        return total;
    }
    private static double cost(int cp) {
        if (Character.getType(cp)==Character.NON_SPACING_MARK || cp==0x200d) return 0;
        if (Character.isWhitespace(cp)) return .3;
        if (cp<128) return Character.isLetterOrDigit(cp) ? .42 : .65;
        return 1;
    }
}
