package com.sunwin.deepseekharness;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Dice {
    static final int[] FACES={4,6,8,10,12,20,100};
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final Pattern FORM=Pattern.compile("([1-9][0-9]{0,2})?[dD](100|20|12|10|8|6|4)(?:\\s*([+-])\\s*([0-9]{1,5}))?");
    final String formula;
    final List<Integer> rolls;
    final int total;

    private Dice(String formula,List<Integer> rolls,int total) {
        this.formula=formula; this.rolls=Collections.unmodifiableList(rolls); this.total=total;
    }
    static Dice roll(String expression) {
        Matcher m=FORM.matcher(expression.trim());
        if (!m.matches()) throw new IllegalArgumentException("dice-expression");
        int n=m.group(1)==null ? 1 : Integer.parseInt(m.group(1));
        if (n>100) throw new IllegalArgumentException("dice-count");
        int faces=Integer.parseInt(m.group(2));
        int modifier=m.group(3)==null ? 0 : Integer.parseInt(m.group(3)+m.group(4));
        String formula=(n==1 ? "" : Integer.toString(n))+"d"+faces+(modifier==0 ? "" : String.format(Locale.ROOT,"%+d",modifier));
        int total=modifier;
        List<Integer> rolls=new ArrayList<>(n);
        for (int i=0;i<n;i++) {
            int die=1+RANDOM.nextInt(faces);
            rolls.add(die); total+=die;
        }
        return new Dice(formula,rolls,total);
    }
    String block() {
        StringBuilder values=new StringBuilder();
        for (int roll:rolls) {
            if (values.length()>0) values.append(',');
            values.append(roll);
        }
        return "[骰子 "+formula+" | "+values+" | "+total+"]";
    }
}
