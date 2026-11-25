package com.sunwin.deepseekharness;

import android.util.Xml;
import org.xmlpull.v1.*;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Only reads selected OOXML text parts. External relationships are never followed. */
final class OfficeText {
    private static final int XML_BYTES=24*1024*1024;
    private final ZipFile zip;
    private int remaining=XML_BYTES;
    private final List<String> shared=new ArrayList<>();
    private OfficeText(ZipFile zip) { this.zip=zip; }

    static void read(File file,String kind,Documents.Text out) throws IOException {
        try (ZipFile zip=new ZipFile(file)) {
            if (zip.size()>2048) throw new IOException("zip-entries");
            OfficeText reader=new OfficeText(zip);
            if ("docx".equals(kind)) reader.part("word/document.xml","word",out);
            else {
                boolean sheets="xlsx".equals(kind);
                if (sheets && zip.getEntry("xl/sharedStrings.xml")!=null) reader.part("xl/sharedStrings.xml","shared",out);
                Pattern names=Pattern.compile(sheets ? "xl/worksheets/sheet([0-9]{1,8})\\.xml" : "ppt/slides/slide([0-9]{1,8})\\.xml");
                TreeMap<Integer,String> parts=new TreeMap<>();
                for (Enumeration<? extends ZipEntry> it=zip.entries();it.hasMoreElements();) {
                    String name=it.nextElement().getName();
                    Matcher m=names.matcher(name);
                    if (m.matches()) parts.put(Integer.parseInt(m.group(1)),name);
                }
                for (String name:parts.values()) {
                    reader.part(name,sheets ? "sheet" : "slide",out);
                    out.write('\n');
                }
            }
        } catch (XmlPullParserException e) { throw new IOException("xml",e); }
    }
    private void part(String name,String mode,Documents.Text out) throws IOException,XmlPullParserException {
        ZipEntry entry=zip.getEntry(name);
        if (entry==null || entry.getSize()<0 || entry.getSize()>remaining) throw new IOException("xml-size");
        try (InputStream source=zip.getInputStream(entry); InputStream in=new FilterInputStream(source) {
            @Override public int read(byte[] b,int off,int len) throws IOException {
                int n=super.read(b,off,len);
                if (n>0 && (remaining-=n)<0) throw new IOException("xml-size");
                return n;
            }
            @Override public int read() throws IOException {
                int c=super.read();
                if (c>=0 && --remaining<0) throw new IOException("xml-size");
                return c;
            }
        }) {
            XmlPullParser p=Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);
            p.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL,false);
            p.setInput(in,null);
            boolean collect=false, cell=false, firstCell=true;
            boolean strings="shared".equals(mode), sheet="sheet".equals(mode);
            String type=null; StringBuilder value=new StringBuilder();
            for (int event=p.nextToken();event!=XmlPullParser.END_DOCUMENT;event=p.nextToken()) {
                String tag=p.getName();
                if (event==XmlPullParser.DOCDECL) throw new IOException("xml-doctype");
                if (event==XmlPullParser.START_TAG) {
                    if ("c".equals(tag) && sheet) {
                        cell=true; type=p.getAttributeValue(null,"t"); value.setLength(0);
                        if (!firstCell) out.write('\t');
                        firstCell=false;
                    } else if ("si".equals(tag) && strings) value.setLength(0);
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
                    } else if ("row".equals(tag) || "p".equals(tag)) { out.write('\n'); firstCell=true; }
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
    }
}
