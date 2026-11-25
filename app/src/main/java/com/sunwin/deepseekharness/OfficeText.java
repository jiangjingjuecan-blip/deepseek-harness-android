package com.sunwin.deepseekharness;

import android.content.Context;
import android.util.Xml;
import org.xmlpull.v1.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Resolve pictures at references in document parts, never by ZIP entry order. */
final class OfficeText {
    private static final int XML_BYTES=24*1024*1024, MEDIA_BYTES=64*1024*1024;
    private final ZipFile zip;
    private final Context ctx;
    private final Documents.Text out;
    private int xmlLeft=XML_BYTES, mediaLeft=MEDIA_BYTES;
    private final List<String> shared=new ArrayList<>();
    private final Map<String,String> decoded=new HashMap<>();

    private OfficeText(ZipFile zip,Context ctx,Documents.Text out) {
        this.zip=zip; this.ctx=ctx; this.out=out;
    }
    static void read(Context ctx,File file,String kind,Documents.Text out) throws IOException {
        try (ZipFile zip=new ZipFile(file)) {
            if (zip.size()>2048) throw new IOException("zip-entries");
            OfficeText book=new OfficeText(zip,ctx,out);
            if ("docx".equals(kind)) book.readText("word/document.xml","word");
            else {
                boolean sheets="xlsx".equals(kind);
                if (sheets && zip.getEntry("xl/sharedStrings.xml")!=null) book.readText("xl/sharedStrings.xml","shared");
                for (String name:book.parts(sheets)) {
                    book.readText(name,sheets ? "sheet" : "slide");
                    out.write('\n');
                }
            }
        } catch (XmlPullParserException e) { throw new IOException("xml",e); }
    }

    private final class Part implements Closeable {
        final InputStream in;
        final XmlPullParser p;
        private final Set<Integer> alternatives=new HashSet<>();
        Part(String name) throws IOException,XmlPullParserException {
            ZipEntry entry=zip.getEntry(name);
            if (entry==null || entry.getSize()<0 || entry.getSize()>xmlLeft) throw new IOException("xml-size");
            p=Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);
            p.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL,false);
            in=new FilterInputStream(zip.getInputStream(entry)) {
                @Override public int read(byte[] b,int off,int len) throws IOException {
                    int n=super.read(b,off,len);
                    if (n>0 && (xmlLeft-=n)<0) throw new IOException("xml-size");
                    return n;
                }
                @Override public int read() throws IOException {
                    int c=super.read();
                    if (c>=0 && --xmlLeft<0) throw new IOException("xml-size");
                    return c;
                }
            };
            try { p.setInput(in,null); } catch (XmlPullParserException e) { in.close(); throw e; }
        }
        int next() throws IOException,XmlPullParserException {
            while (true) {
                int event=token(), depth=p.getDepth();
                String tag=p.getName();
                if (event==XmlPullParser.START_TAG && ("Choice".equals(tag) || "Fallback".equals(tag))) {
                    // Modern drawings often repeat the same picture in a legacy fallback.
                    if (!alternatives.add(depth)) {
                        int skipped;
                        do {
                            skipped=token();
                            if (skipped==XmlPullParser.END_DOCUMENT) throw new IOException("xml-alternative");
                        } while (skipped!=XmlPullParser.END_TAG || p.getDepth()!=depth);
                        continue;
                    }
                }
                if (event==XmlPullParser.END_TAG && "AlternateContent".equals(tag)) alternatives.remove(depth+1);
                return event;
            }
        }
        private int token() throws IOException,XmlPullParserException {
            int event=p.nextToken();
            if (event==XmlPullParser.DOCDECL) throw new IOException("xml-doctype");
            return event;
        }
        @Override public void close() throws IOException { in.close(); }
    }
    private static String reference(XmlPullParser p,String attr) {
        for (int i=0;i<p.getAttributeCount();i++)
            if (attr.equals(p.getAttributeName(i)) && p.getAttributeNamespace(i).endsWith("/relationships"))
                return p.getAttributeValue(i);
        return null;
    }
    private Map<String,String> relations(String name) throws IOException,XmlPullParserException {
        Map<String,String> result=new HashMap<>();
        int slash=name.lastIndexOf('/')+1;
        String folder=name.substring(0,slash);
        String rels=folder+"_rels/"+name.substring(slash)+".rels";
        if (zip.getEntry(rels)==null) return result;
        try (Part xml=new Part(rels)) {
            XmlPullParser p=xml.p;
            for (int e=xml.next();e!=XmlPullParser.END_DOCUMENT;e=xml.next()) {
                if (e!=XmlPullParser.START_TAG || !"Relationship".equals(p.getName()) ||
                        "External".equalsIgnoreCase(p.getAttributeValue(null,"TargetMode"))) continue;
                String id=p.getAttributeValue(null,"Id"), target=p.getAttributeValue(null,"Target");
                if (id==null || target==null) continue;
                try {
                    URI uri=new URI(target);
                    String path=uri.getPath();
                    if (uri.isAbsolute() || uri.getRawAuthority()!=null || path==null || path.isEmpty()) continue;
                    path=Paths.get(path.startsWith("/") ? path.substring(1) : folder+path).normalize().toString();
                    if (zip.getEntry(path)!=null) result.put(id,path);
                } catch (URISyntaxException | InvalidPathException ignored) { }
            }
        }
        return result;
    }
    private List<String> parts(boolean sheets) throws IOException,XmlPullParserException {
        String index=sheets ? "xl/workbook.xml" : "ppt/presentation.xml";
        String prefix=sheets ? "xl/worksheets/" : "ppt/slides/";
        List<String> ordered=new ArrayList<>();
        if (zip.getEntry(index)!=null) {
            Map<String,String> rels=relations(index);
            try (Part xml=new Part(index)) {
                for (int e=xml.next();e!=XmlPullParser.END_DOCUMENT;e=xml.next()) {
                    if (e==XmlPullParser.START_TAG && (sheets ? "sheet" : "sldId").equals(xml.p.getName())) {
                        String path=rels.get(reference(xml.p,"id"));
                        if (path!=null && path.startsWith(prefix)) ordered.add(path);
                    }
                }
            }
            if (!ordered.isEmpty()) return ordered;
        }
        Pattern pattern=Pattern.compile(prefix+(sheets ? "sheet" : "slide")+"([0-9]{1,8})\\.xml");
        TreeMap<Integer,String> fallback=new TreeMap<>();
        for (Enumeration<? extends ZipEntry> entries=zip.entries();entries.hasMoreElements();) {
            String name=entries.nextElement().getName();
            Matcher m=pattern.matcher(name);
            if (m.matches()) fallback.put(Integer.parseInt(m.group(1)),name);
        }
        return new ArrayList<>(fallback.values());
    }
    private boolean picture(String path,String prefix) throws IOException {
        if (path==null || !path.startsWith(prefix) || !out.canTakeImage()) return false;
        if (!decoded.containsKey(path)) {
            ZipEntry entry=zip.getEntry(path);
            long size=entry.getSize();
            String jpeg=null;
            if (size>0 && size<=Math.min(Documents.MAX_INPUT,mediaLeft)) {
                mediaLeft-=(int)size;
                try (InputStream in=zip.getInputStream(entry)) { jpeg=Photos.importEmbedded(ctx,in); }
            }
            decoded.put(path,jpeg);
        }
        String jpeg=decoded.get(path);
        out.image(jpeg);
        return jpeg!=null;
    }
    private static int number(String value,int fallback,int max) {
        try { int n=Integer.parseInt(value); return n>=0 && n<=max ? n : fallback; }
        catch (NumberFormatException e) { return fallback; }
    }
    private static int column(String cell,int fallback) {
        if (cell==null) return fallback;
        int col=0;
        for (int i=0;i<cell.length();i++) {
            char c=cell.charAt(i);
            if (c<'A' || c>'Z') break;
            col=col*26+c-'A'+1;
            if (col>16384) return fallback;
        }
        return col==0 ? fallback : col-1;
    }
    private static long cellKey(int row,int col) { return ((long)row<<16)+col; }

    private NavigableMap<Long,List<String>> sheetPictures(String sheet,Map<String,String> rels)
            throws IOException,XmlPullParserException {
        NavigableMap<Long,List<String>> anchors=new TreeMap<>();
        if (!out.canTakeImage() || rels.values().stream().noneMatch(p->p.startsWith("xl/drawings/"))) return anchors;
        // A worksheet names its drawing after sheetData. Read the reference first so
        // images anchored to empty cells and rows can be emitted during the text pass.
        try (Part xml=new Part(sheet)) {
            for (int e=xml.next();e!=XmlPullParser.END_DOCUMENT;e=xml.next()) {
                if (e==XmlPullParser.START_TAG && "drawing".equals(xml.p.getName())) {
                    String drawing=rels.get(reference(xml.p,"id"));
                    if (drawing!=null && drawing.startsWith("xl/drawings/")) drawing(drawing,anchors);
                }
            }
        }
        return anchors;
    }
    private void drawing(String name,Map<Long,List<String>> anchors) throws IOException,XmlPullParserException {
        Map<String,String> rels=relations(name);
        try (Part xml=new Part(name)) {
            XmlPullParser p=xml.p;
            int row=0,col=0; boolean from=false;
            String coord=null; StringBuilder value=new StringBuilder();
            for (int e=xml.next();e!=XmlPullParser.END_DOCUMENT;e=xml.next()) {
                String tag=p.getName();
                if (e==XmlPullParser.START_TAG) {
                    if (tag.endsWith("Anchor")) { row=0; col=0; }
                    else if ("from".equals(tag)) from=true;
                    else if (from && ("row".equals(tag) || "col".equals(tag))) { coord=tag; value.setLength(0); }
                    else if ("pos".equals(tag)) {
                        // Absolute anchors have no cell address. Use the default grid's EMU spacing.
                        col=(int)Math.min(16383,emu(p,"x")/609600);
                        row=(int)Math.min(1048575,emu(p,"y")/190500);
                    } else if ("blip".equals(tag)) {
                        String path=rels.get(reference(p,"embed"));
                        if (path!=null && path.startsWith("xl/media/"))
                            anchors.computeIfAbsent(cellKey(row,col),k->new ArrayList<>()).add(path);
                    }
                } else if (e==XmlPullParser.END_TAG) {
                    if ("from".equals(tag)) from=false;
                    if (tag.equals(coord)) {
                        if ("row".equals(tag)) row=number(value.toString().trim(),0,1048575);
                        else col=number(value.toString().trim(),0,16383);
                        coord=null;
                    }
                } else if (coord!=null && e==XmlPullParser.TEXT && value.length()<16) value.append(p.getText());
            }
        }
    }
    private static long emu(XmlPullParser p,String attr) {
        try { return Math.max(0,Long.parseLong(p.getAttributeValue(null,attr))); }
        catch (NumberFormatException e) { return 0; }
    }
    private void through(NavigableMap<Long,List<String>> anchors,long key,char separator) throws IOException {
        while (!anchors.isEmpty() && anchors.firstKey()<=key)
            for (String path:anchors.pollFirstEntry().getValue()) if (picture(path,"xl/media/")) out.write(separator);
    }
    private void readText(String name,String mode) throws IOException,XmlPullParserException {
        boolean strings="shared".equals(mode), sheet="sheet".equals(mode);
        Map<String,String> rels=strings ? Collections.emptyMap() : relations(name);
        String media="word".equals(mode) ? "word/media/" : "ppt/media/";
        NavigableMap<Long,List<String>> anchors=sheet ? sheetPictures(name,rels) : new TreeMap<>();
        try (Part xml=new Part(name)) {
            XmlPullParser p=xml.p;
            boolean collect=false, cell=false, firstCell=true;
            int row=-1,col=-1;
            String type=null; StringBuilder value=new StringBuilder();
            for (int event=xml.next();event!=XmlPullParser.END_DOCUMENT;event=xml.next()) {
                String tag=p.getName();
                if (event==XmlPullParser.START_TAG) {
                    if (sheet && "row".equals(tag)) {
                        row=number(p.getAttributeValue(null,"r"),row+2,1048576)-1; col=-1;
                        through(anchors,cellKey(row,0)-1,'\n');
                    } else if (sheet && "c".equals(tag)) {
                        cell=true; type=p.getAttributeValue(null,"t"); value.setLength(0);
                        if (!firstCell) out.write('\t');
                        firstCell=false;
                        col=column(p.getAttributeValue(null,"r"),col+1);
                        through(anchors,cellKey(row,col),'\t');
                    } else if ("si".equals(tag) && strings) value.setLength(0);
                    else if (!sheet && !strings && ("blip".equals(tag) || "imagedata".equals(tag)))
                        picture(rels.get(reference(p,"blip".equals(tag) ? "embed" : "id")),media);
                    if ("t".equals(tag) || ("v".equals(tag) && cell)) collect=true;
                    if ("tab".equals(tag)) out.write('\t');
                    if ("br".equals(tag)) out.write('\n');
                } else if (event==XmlPullParser.END_TAG) {
                    if ("t".equals(tag) || "v".equals(tag)) collect=false;
                    if ("si".equals(tag) && strings) {
                        if (shared.size()>=65536) throw new IOException("shared-strings");
                        shared.add(value.toString());
                    } else if ("c".equals(tag) && sheet) {
                        if ("s".equals(type)) {
                            try { out.write(shared.get(Integer.parseInt(value.toString().trim()))); }
                            catch (IndexOutOfBoundsException | NumberFormatException e) { throw new IOException("shared-index"); }
                        } else out.write(value.toString());
                        cell=false;
                    } else if ("row".equals(tag) || "p".equals(tag)) {
                        if (sheet) through(anchors,cellKey(row,65535),'\t');
                        out.write('\n'); firstCell=true;
                    }
                } else if (collect && (event==XmlPullParser.TEXT || event==XmlPullParser.CDSECT || event==XmlPullParser.ENTITY_REF)) {
                    String text=p.getText();
                    if (text==null) throw new IOException("xml-entity");
                    if (strings || cell) {
                        if (value.length()+text.length()>Documents.MAX_CHARS) throw new IOException("cell-size");
                        value.append(text);
                    } else out.write(text);
                }
            }
        }
        through(anchors,Long.MAX_VALUE,'\n');
    }
}
