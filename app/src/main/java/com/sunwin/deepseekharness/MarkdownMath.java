package com.sunwin.deepseekharness;

import java.util.*;
import java.util.regex.*;

/**
 * Pure Markdown + LaTeX math block/inline splitter.
 * Keeps incomplete streaming math as plain text, protects currency like "$5 and $10",
 * and preserves exact raw source text for copy/selection.
 */
final class MarkdownMath {
    enum BlockType { PROSE, DISPLAY_MATH, CODE_BLOCK, TABLE }

    static final class Block {
        final BlockType type;
        final String raw, content, info;
        Block(BlockType type,String raw,String content,String info) {
            this.type=type; this.raw=raw; this.content=content; this.info=info==null?"":info;
        }
    }

    static final class InlineSpan {
        final String raw, latex;
        InlineSpan(String raw,String latex) { this.raw=raw; this.latex=latex; }
    }

    static final class InlineResult {
        final String text;
        final List<InlineSpan> spans;
        InlineResult(String text,List<InlineSpan> spans) { this.text=text; this.spans=spans; }
    }

    static final class TableData {
        final List<String> headers;
        final List<Integer> alignments; // -1 left, 0 center, 1 right
        final List<List<String>> rows;
        TableData(List<String> headers,List<Integer> alignments,List<List<String>> rows) {
            this.headers=headers; this.alignments=alignments; this.rows=rows;
        }
    }

    static TableData parseTable(String raw) {
        String[] lines=raw.split("\n");
        if (lines.length<2) return null;
        List<String> headers=splitTableRow(lines[0]);
        List<String> sep=splitTableRow(lines[1]);
        int cols=Math.max(headers.size(),sep.size());
        if (cols==0) return null;
        while (headers.size()<cols) headers.add("");
        List<Integer> alignments=new ArrayList<>(cols);
        for (int i=0;i<cols;i++) {
            String cell=i<sep.size() ? sep.get(i).trim() : "";
            boolean left=cell.startsWith(":"), right=cell.endsWith(":");
            alignments.add(left && right ? 0 : right ? 1 : -1);
        }
        List<List<String>> rows=new ArrayList<>();
        for (int i=2;i<lines.length;i++) {
            List<String> cells=splitTableRow(lines[i]);
            while (cells.size()<cols) cells.add("");
            if (cells.size()>cols) cells=new ArrayList<>(cells.subList(0,cols));
            rows.add(cells);
        }
        return new TableData(headers,alignments,rows);
    }

    private static List<String> splitTableRow(String line) {
        String s=line.trim();
        if (s.startsWith("|")) s=s.substring(1);
        if (s.endsWith("|") && !isEscaped(s,s.length()-1)) s=s.substring(0,s.length()-1);
        List<String> cells=new ArrayList<>();
        StringBuilder cur=new StringBuilder();
        int n=s.length();
        for (int i=0;i<n;i++) {
            char c=s.charAt(i);
            if (c=='`') {
                int end=skipInlineCode(s,i);
                if (end>i) { cur.append(s,i,end); i=end-1; continue; }
            }
            if (c=='\\' && i+1<n && s.charAt(i+1)=='|') {
                cur.append('|'); i++; continue;
            }
            if (c=='|') {
                cells.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cells.add(cur.toString().trim());
        return cells;
    }

    static final char PLACEHOLDER_START='\uE000';
    static final char PLACEHOLDER_END='\uE001';
    private static final Pattern FENCE_OPEN=Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern TABLE_SEP=Pattern.compile("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$|^\\s*\\|\\s*:?-{3,}:?\\s*\\|\\s*$");
    private static final Pattern BIG_PIPE=Pattern.compile("\\\\([Bb]igg?)\\|");
    private static final Pattern OPERATOR_NAME=Pattern.compile("\\\\operatorname\\*?\\s*\\{([^{}]+)\\}");

    static String placeholder(int index) {
        return ""+PLACEHOLDER_START+index+PLACEHOLDER_END;
    }

    /** Fast check so plain replies keep the zero-overhead append fast path. */
    static boolean hasRichSyntax(String text) {
        if (text==null || text.isEmpty()) return false;
        int n=text.length();
        for (int i=0;i<n;i++) {
            char c=text.charAt(i);
            switch (c) {
                case '*': case '`': case '$': case '#': case '~': case '|':
                    return true;
                case '\\':
                    if (i+1<n) {
                        char next=text.charAt(i+1);
                        if (next=='(' || next=='[') return true;
                    }
                    break;
                case '[':
                    if (text.indexOf("](",i)>i) return true;
                    break;
                case '_':
                    if (i==0 || !Character.isLetterOrDigit(text.charAt(i-1))) return true;
                    break;
                case '>':
                    if (i==0 || text.charAt(i-1)=='\n') return true;
                    break;
                case '-': case '+':
                    if ((i==0 || text.charAt(i-1)=='\n') && i+1<n && (text.charAt(i+1)==' ' || text.charAt(i+1)=='-')) return true;
                    break;
                default:
                    if (c>='0' && c<='9' && (i==0 || text.charAt(i-1)=='\n')) {
                        int j=i+1;
                        while (j<n && text.charAt(j)>='0' && text.charAt(j)<='9') j++;
                        if (j+1<n && text.charAt(j)=='.' && text.charAt(j+1)==' ') return true;
                    }
            }
        }
        return false;
    }

    static List<Block> parseBlocks(String source) {
        if (source==null || source.isEmpty()) return Collections.emptyList();
        List<Block> blocks=new ArrayList<>();
        String[] lines=source.split("\n",-1);
        StringBuilder prose=new StringBuilder();
        int i=0;
        while (i<lines.length) {
            String line=lines[i];
            // 1. Fenced code block (``` or ~~~)
            Matcher fence=FENCE_OPEN.matcher(line);
            if (fence.matches()) {
                String marker=fence.group(1);
                String info=fence.group(2).trim();
                char ch=marker.charAt(0);
                // Backtick fences cannot contain backticks in info string per CommonMark.
                if (ch!='`' || info.indexOf('`')<0) {
                    flushProse(blocks,prose);
                    StringBuilder raw=new StringBuilder(line);
                    StringBuilder code=new StringBuilder();
                    int j=i+1;
                    while (j<lines.length) {
                        String cur=lines[j];
                        raw.append('\n').append(cur);
                        if (isClosingFence(cur,ch,marker.length())) { j++; break; }
                        if (code.length()>0) code.append('\n');
                        code.append(cur);
                        j++;
                    }
                    blocks.add(new Block(BlockType.CODE_BLOCK,raw.toString(),code.toString(),info));
                    i=j;
                    continue;
                }
            }
            // 2. GFM pipe table (header row with | followed by delimiter row)
            if (i+1<lines.length && line.indexOf('|')>=0 && TABLE_SEP.matcher(lines[i+1]).matches()) {
                flushProse(blocks,prose);
                StringBuilder raw=new StringBuilder(line).append('\n').append(lines[i+1]);
                int j=i+2;
                while (j<lines.length && !lines[j].trim().isEmpty() && lines[j].indexOf('|')>=0
                        && !FENCE_OPEN.matcher(lines[j]).matches()) {
                    raw.append('\n').append(lines[j]);
                    j++;
                }
                blocks.add(new Block(BlockType.TABLE,raw.toString(),raw.toString(),""));
                i=j;
                continue;
            }
            // 3. Multi-line or standalone display math: $$ ... $$ or \[ ... \]
            int mathEnd=tryBlockDisplayMath(lines,i,blocks,prose);
            if (mathEnd>i) {
                i=mathEnd;
                continue;
            }
            if (prose.length()>0) prose.append('\n');
            prose.append(line);
            i++;
        }
        flushProse(blocks,prose);
        return blocks;
    }

    private static boolean isClosingFence(String line,char ch,int minLen) {
        int pos=0, n=line.length();
        while (pos<n && pos<3 && line.charAt(pos)==' ') pos++;
        int count=0;
        while (pos<n && line.charAt(pos)==ch) { count++; pos++; }
        if (count<minLen) return false;
        while (pos<n) {
            char c=line.charAt(pos++);
            if (c!=' ' && c!='\t' && c!='\r') return false;
        }
        return true;
    }

    /**
     * Checks if line `start` begins a multi-line `$$` or `\[` block (outside inline code).
     * Single-line or embedded `$$...$$` / `\[...\]` are handled in `flushProse` so text
     * before and after on the same line is cleanly split.
     */
    private static int tryBlockDisplayMath(String[] lines,int start,List<Block> blocks,StringBuilder prose) {
        String trimmed=lines[start].trim();
        String openDelim=null, closeDelim=null;
        if (trimmed.equals("$$")) { openDelim="$$"; closeDelim="$$"; }
        else if (trimmed.equals("\\[")) { openDelim="\\["; closeDelim="\\]"; }
        else if (trimmed.startsWith("$$") && findUnescaped(trimmed,"$$",2)<0) { openDelim="$$"; closeDelim="$$"; }
        else if (trimmed.startsWith("\\[") && findUnescaped(trimmed,"\\]",2)<0) { openDelim="\\["; closeDelim="\\]"; }
        if (openDelim==null) return -1;

        StringBuilder raw=new StringBuilder(lines[start]);
        StringBuilder inner=new StringBuilder();
        String firstRest=trimmed.substring(openDelim.length());
        if (!firstRest.isEmpty()) inner.append(firstRest);
        for (int j=start+1;j<lines.length;j++) {
            String cur=lines[j];
            raw.append('\n').append(cur);
            int closeAt=findUnescaped(cur,closeDelim,0);
            if (closeAt>=0) {
                String before=cur.substring(0,closeAt);
                String after=cur.substring(closeAt+closeDelim.length()).trim();
                if (!before.trim().isEmpty()) {
                    if (inner.length()>0) inner.append('\n');
                    inner.append(before);
                }
                String latex=inner.toString().trim();
                if (!latex.isEmpty()) {
                    flushProse(blocks,prose);
                    blocks.add(new Block(BlockType.DISPLAY_MATH,raw.toString(),latex,""));
                    if (!after.isEmpty()) prose.append(after);
                    return j+1;
                }
                return -1;
            }
            if (inner.length()>0) inner.append('\n');
            inner.append(cur);
        }
        // Unclosed display math (e.g. mid-typewriter reveal): keep as plain text until closed.
        return -1;
    }

    /** Flush accumulated prose, splitting out any closed inline-embedded $$...$$ or \[...\] blocks. */
    private static void flushProse(List<Block> blocks,StringBuilder prose) {
        if (prose.length()==0) return;
        String text=prose.toString();
        prose.setLength(0);
        splitEmbeddedDisplayMath(blocks,text);
    }

    private static void splitEmbeddedDisplayMath(List<Block> blocks,String text) {
        int n=text.length();
        int segStart=0;
        int i=0;
        while (i<n) {
            char c=text.charAt(i);
            // Skip inline code spans (`...`)
            if (c=='`') {
                int end=skipInlineCode(text,i);
                i=end>i ? end : i+1;
                continue;
            }
            if (c=='\\' && i+1<n) {
                char next=text.charAt(i+1);
                if (next=='[' && !isEscaped(text,i)) {
                    int close=findUnescaped(text,"\\]",i+2);
                    if (close>i+2) {
                        String inner=text.substring(i+2,close).trim();
                        if (!inner.isEmpty()) {
                            addTrimmedProse(blocks,text.substring(segStart,i));
                            blocks.add(new Block(BlockType.DISPLAY_MATH,text.substring(i,close+2),inner,""));
                            i=close+2;
                            segStart=i;
                            continue;
                        }
                    }
                }
                i+=2;
                continue;
            }
            if (c=='$' && i+1<n && text.charAt(i+1)=='$' && !isEscaped(text,i)) {
                int close=findUnescaped(text,"$$",i+2);
                if (close>i+2) {
                    String inner=text.substring(i+2,close).trim();
                    if (!inner.isEmpty()) {
                        addTrimmedProse(blocks,text.substring(segStart,i));
                        blocks.add(new Block(BlockType.DISPLAY_MATH,text.substring(i,close+2),inner,""));
                        i=close+2;
                        segStart=i;
                        continue;
                    }
                }
                // Unclosed $$: leave the rest as plain prose.
                break;
            }
            i++;
        }
        if (segStart<n) addTrimmedProse(blocks,text.substring(segStart));
    }

    private static void addTrimmedProse(List<Block> blocks,String raw) {
        String trimmed=trimBlankLines(raw);
        if (!trimmed.isEmpty()) blocks.add(new Block(BlockType.PROSE,trimmed,trimmed,""));
    }

    private static String trimBlankLines(String s) {
        int start=0, end=s.length();
        while (start<end) {
            int nl=s.indexOf('\n',start);
            int lineEnd=nl<0 ? end : nl;
            if (isBlank(s,start,lineEnd)) start=nl<0 ? end : nl+1;
            else break;
        }
        while (end>start) {
            int nl=s.lastIndexOf('\n',end-1);
            int lineStart=nl<0 ? start : nl+1;
            if (isBlank(s,lineStart,end)) end=nl<0 ? start : nl;
            else break;
        }
        return s.substring(start,end);
    }

    private static boolean isBlank(String s,int from,int to) {
        for (int i=from;i<to;i++) {
            char c=s.charAt(i);
            if (c!=' ' && c!='\t' && c!='\r') return false;
        }
        return true;
    }

    /**
     * Extract validated inline math (`$...$` and `\(...\)`) from prose/table text,
     * replacing each formula with a PUA placeholder so CommonMark never mangles `_`, `*`, `\`.
     */
    static InlineResult extractInlineMath(String prose) {
        if (prose==null || prose.isEmpty()) return new InlineResult("",Collections.emptyList());
        StringBuilder out=new StringBuilder(prose.length());
        List<InlineSpan> spans=new ArrayList<>();
        int n=prose.length();
        int i=0;
        while (i<n) {
            char c=prose.charAt(i);
            // Inline code spans must never be parsed as math.
            if (c=='`') {
                int end=skipInlineCode(prose,i);
                if (end>i) { out.append(prose,i,end); i=end; continue; }
            }
            // Escaped dollar or backslash
            if (c=='\\' && i+1<n) {
                char next=prose.charAt(i+1);
                if (next=='(' && !isEscaped(prose,i)) {
                    int close=findInlineParenClose(prose,i+2);
                    if (close>i+2) {
                        String inner=prose.substring(i+2,close).trim();
                        if (!inner.isEmpty()) {
                            int idx=spans.size();
                            spans.add(new InlineSpan(prose.substring(i,close+2),inner));
                            out.append(placeholder(idx));
                            i=close+2;
                            continue;
                        }
                    }
                }
                if (next=='$') {
                    out.append("\\$");
                    i+=2;
                    continue;
                }
                out.append(c);
                i++;
                continue;
            }
            // Single-$ inline math with currency guards
            if (c=='$' && !isEscaped(prose,i)) {
                // Skip $$ (unclosed display math left as literal text)
                if (i+1<n && prose.charAt(i+1)=='$') {
                    out.append("$$");
                    i+=2;
                    continue;
                }
                int close=findInlineDollarClose(prose,i);
                if (close>i+1) {
                    String inner=prose.substring(i+1,close);
                    int idx=spans.size();
                    spans.add(new InlineSpan(prose.substring(i,close+1),inner));
                    out.append(placeholder(idx));
                    i=close+1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return new InlineResult(out.toString(),spans);
    }

    /**
     * Find closing `$` for an opening `$` at `open`, enforcing currency guards:
     * - Character after opening `$` must not be whitespace.
     * - Must stay on the same line (never cross `\n` or `\r`).
     * - Character before closing `$` must not be whitespace.
     * - Character after closing `$` must not be an ASCII digit ('0'..'9').
     * - If a candidate closing `$` fails those guards (e.g. "$5 and $10"), abort this opener
     *   immediately so a later valid "$x$" on the same line still parses cleanly.
     */
    private static int findInlineDollarClose(String s,int open) {
        int n=s.length();
        if (open+1>=n) return -1;
        char first=s.charAt(open+1);
        if (first==' ' || first=='\t' || first=='\n' || first=='\r' || first=='$') return -1;
        for (int j=open+1;j<n;j++) {
            char ch=s.charAt(j);
            if (ch=='\n' || ch=='\r') return -1;
            if (ch=='\\' && j+1<n) { j++; continue; }
            if (ch=='$') {
                if (j+1<n && s.charAt(j+1)=='$') return -1;
                char prev=s.charAt(j-1);
                if (prev==' ' || prev=='\t') return -1;
                if (j+1<n) {
                    char after=s.charAt(j+1);
                    if (after>='0' && after<='9') return -1;
                }
                return j;
            }
        }
        return -1;
    }

    private static int findInlineParenClose(String s,int from) {
        int n=s.length();
        for (int j=from;j<n;j++) {
            char ch=s.charAt(j);
            if (ch=='\n' && j+1<n && s.charAt(j+1)=='\n') return -1;
            if (ch=='\\' && j+1<n) {
                if (s.charAt(j+1)==')' && !isEscaped(s,j)) return j;
                j++;
            }
        }
        return -1;
    }

    private static int skipInlineCode(String s,int open) {
        int n=s.length();
        int ticks=0;
        while (open+ticks<n && s.charAt(open+ticks)=='`') ticks++;
        int j=open+ticks;
        while (j<n) {
            if (s.charAt(j)=='`') {
                int count=0;
                while (j+count<n && s.charAt(j+count)=='`') count++;
                if (count==ticks) return j+count;
                j+=count;
            } else j++;
        }
        return -1;
    }

    private static int findUnescaped(String s,String needle,int from) {
        int idx=from;
        while ((idx=s.indexOf(needle,idx))>=0) {
            if (!isEscaped(s,idx)) return idx;
            idx+=needle.length();
        }
        return -1;
    }

    private static boolean isEscaped(String s,int pos) {
        int slashes=0;
        for (int k=pos-1;k>=0 && s.charAt(k)=='\\';k--) slashes++;
        return (slashes & 1)==1;
    }

    /** Normalize LaTeX constructs for reliable JLatexMath rendering. */
    static String normalizeLatex(String latex) {
        if (latex==null) return "";
        String s=latex.trim();
        // JLatexMath supports aligned/gathered environments; map starred/top-level aliases.
        s=s.replace("\\begin{align*}","\\begin{aligned}")
           .replace("\\end{align*}","\\end{aligned}")
           .replace("\\begin{align}","\\begin{aligned}")
           .replace("\\end{align}","\\end{aligned}")
           .replace("\\begin{gather*}","\\begin{gathered}")
           .replace("\\end{gather*}","\\end{gathered}")
           .replace("\\begin{gather}","\\begin{gathered}")
           .replace("\\end{gather}","\\end{gathered}")
           .replace("\\begin{equation*}","")
           .replace("\\end{equation*}","")
           .replace("\\begin{equation}","")
           .replace("\\end{equation}","");
        // JLatexMath handles \Bigg\vert reliably; normalize \big|..\Bigg| to \vert.
        s=BIG_PIPE.matcher(s).replaceAll("\\\\$1\\\\vert ");
        // Normalize \operatorname{foo} to \mathrm{foo}.
        s=OPERATOR_NAME.matcher(s).replaceAll("\\\\mathrm{$1}");
        // Map common Unicode math symbols that models occasionally emit inside formulas.
        s=s.replace("≤","\\le ")
           .replace("≥","\\ge ")
           .replace("≠","\\ne ")
           .replace("≈","\\approx ")
           .replace("×","\\times ")
           .replace("·","\\cdot ")
           .replace("±","\\pm ")
           .replace("∞","\\infty ")
           .replace("→","\\to ")
           .replace("⇒","\\Rightarrow ")
           .replace("∈","\\in ")
           .replace("−","-");
        return s.trim();
    }
}
