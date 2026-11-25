package com.sunwin.deepseekharness;

import android.content.Context;
import java.io.*;
import java.nio.file.Path;
import java.util.*;

final class Attachment {
    enum Kind { PHOTO, TEXT, SCAN }
    static final int MAX_PHOTOS=9, MAX_PROMPT_IMAGES=20, MAX_EMBEDDED_IMAGES=10, MAX_DOCUMENTS=4;
    final Kind kind;
    final String name;
    final int textBytes;
    final List<String> paths;
    // TEXT owns its UTF-8 body first, then the JPEGs. Offsets point into the decoded body.
    final List<Integer> imageOffsets;
    long id; // Assigned by SQLite. Zero while importing.
    Attachment(Kind kind,String name,int textBytes,List<String> paths) {
        this(kind,name,textBytes,paths,Collections.emptyList());
    }
    Attachment(Kind kind,String name,int textBytes,List<String> paths,List<Integer> imageOffsets) {
        this.kind=kind; this.name=name; this.textBytes=textBytes;
        this.paths=Collections.unmodifiableList(new ArrayList<>(paths));
        this.imageOffsets=Collections.unmodifiableList(new ArrayList<>(imageOffsets));
        if (kind==Kind.TEXT && this.paths.size()!=1+this.imageOffsets.size())
            throw new IllegalArgumentException("document-image-offsets");
    }
    static Attachment photo(String path) {
        return new Attachment(Kind.PHOTO,"照片.jpg",0,Collections.singletonList(path));
    }
    static String marker(int number) { return "[图 "+number+"]"; }
    List<String> imagePaths() { return kind==Kind.TEXT ? paths.subList(1,paths.size()) : paths; }
    int imageCount() { return imagePaths().size(); }
    static int photos(List<Attachment> items) {
        int count=0;
        for (Attachment item:items) if (item.kind==Kind.PHOTO) count++;
        return count;
    }
    static int documentImageSlots(List<Attachment> items) {
        int count=0;
        for (Attachment item:items) if (item.kind!=Kind.PHOTO) count+=item.imageCount();
        return Math.min(count,MAX_PROMPT_IMAGES-photos(items));
    }
    static int documents(List<Attachment> items) {
        int count=0;
        for (Attachment item:items) if (item.kind!=Kind.PHOTO) count++;
        return count;
    }
    static File file(Context context,String path) throws IOException {
        Path base=context.getFilesDir().getCanonicalFile().toPath();
        Path actual=base.resolve(path).toFile().getCanonicalFile().toPath();
        if (!actual.startsWith(base.resolve("photos")) && !actual.startsWith(base.resolve("documents")))
            throw new IOException("attachment-path");
        return actual.toFile();
    }
    static String newPath(Context context,String folder,String suffix) throws IOException {
        File dir=new File(context.getFilesDir(),folder);
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("attachment-dir");
        return folder+"/attachment-"+UUID.randomUUID()+suffix;
    }
}
