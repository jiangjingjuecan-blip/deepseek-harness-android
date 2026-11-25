package com.sunwin.deepseekharness;

import java.util.*;

/** Decides when to request AI-generated chat titles and normalizes the result. */
final class ChatTitle {
    static final String SOURCE_AUTO="auto";
    static final String SOURCE_AI="ai";
    static final String SOURCE_USER="user";

    static boolean shouldRequest(History.Chat chat,int completedTurnsBefore) {
        if (chat==null || SOURCE_USER.equals(chat.titleSource)) return false;
        int nextTurn=completedTurnsBefore+1;
        if (nextTurn==1) return true;
        if (nextTurn==3 && chat.titleTurns<3) return true;
        return SOURCE_AUTO.equals(chat.titleSource) && chat.titleTurns==0;
    }

    static String sanitize(String raw) {
        if (raw==null) return "";
        String line="";
        for (String part:raw.split("\\r?\\n")) {
            String trimmed=part.trim();
            if (!trimmed.isEmpty()) { line=trimmed; break; }
        }
        if (line.isEmpty()) return "";
        line=line.replaceAll("\\s+"," ").trim();
        line=stripWrapping(line);
        line=stripTrailingPunctuation(line);
        line=stripWrapping(line);
        line=stripTrailingPunctuation(line);
        if (line.isEmpty()) return "";
        int cjk=0, asciiWord=0;
        for (int i=0;i<line.length();) {
            int cp=line.codePointAt(i);
            if (isCjk(cp)) cjk++;
            else if (Character.isLetterOrDigit(cp)) asciiWord++;
            i+=Character.charCount(cp);
        }
        if (cjk>0) {
            double units=0;
            int cut=line.length();
            for (int i=0;i<line.length();) {
                int cp=line.codePointAt(i);
                double step=isCjk(cp) ? 1.0 : 0.45;
                if (units+step>12.01) { cut=i; break; }
                units+=step;
                i+=Character.charCount(cp);
            }
            if (cut<line.length()) line=line.substring(0,cut).trim();
        } else {
            String[] words=line.split(" ");
            if (words.length>6) {
                StringBuilder sb=new StringBuilder();
                for (int i=0;i<6;i++) { if (i>0) sb.append(' '); sb.append(words[i]); }
                line=sb.toString();
            }
            if (line.length()>42) line=line.substring(0,42).trim();
        }
        return stripTrailingPunctuation(line);
    }

    private static String stripWrapping(String s) {
        String[][] pairs={{"\"","\""},{"'","'"},{"“","”"},{"‘","’"},{"「","」"},{"『","』"},{"《","》"},{"`","`"}};
        boolean changed=true;
        while (changed && !s.isEmpty()) {
            changed=false;
            for (String[] p:pairs) {
                if (s.length()>=p[0].length()+p[1].length() && s.startsWith(p[0]) && s.endsWith(p[1])) {
                    s=s.substring(p[0].length(),s.length()-p[1].length()).trim();
                    changed=true;
                }
            }
            while (!s.isEmpty() && "\"'“”‘’`".indexOf(s.charAt(0))>=0) { s=s.substring(1).trim(); changed=true; }
            while (!s.isEmpty() && "\"'“”‘’`".indexOf(s.charAt(s.length()-1))>=0) { s=s.substring(0,s.length()-1).trim(); changed=true; }
        }
        return s;
    }

    private static String stripTrailingPunctuation(String s) {
        while (!s.isEmpty()) {
            char last=s.charAt(s.length()-1);
            if ("。！？.!?，,、：:;；…—-".indexOf(last)>=0) s=s.substring(0,s.length()-1).trim();
            else break;
        }
        return s;
    }

    private static boolean isCjk(int cp) {
        return (cp>=0x4E00 && cp<=0x9FFF) || (cp>=0x3400 && cp<=0x4DBF) || (cp>=0xF900 && cp<=0xFAFF);
    }

    static String transcript(List<History.Message> rows) {
        StringBuilder sb=new StringBuilder();
        int count=0;
        for (History.Message m:rows) {
            if (!"user".equals(m.role) && !"assistant".equals(m.role)) continue;
            if (m.text==null || m.text.trim().isEmpty()) continue;
            if (sb.length()>0) sb.append('\n');
            sb.append("user".equals(m.role) ? "User: " : "Assistant: ");
            String t=m.text.trim();
            if (t.length()>240) t=t.substring(0,240);
            sb.append(t);
            if (++count>=6 || sb.length()>=1000) break;
        }
        return sb.toString();
    }
}
