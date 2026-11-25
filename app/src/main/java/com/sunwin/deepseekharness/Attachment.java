package com.sunwin.deepseekharness;

import android.content.Context;
import java.io.*;
import java.nio.file.Path;
import java.util.*;

final class Attachment {
    enum Kind { PHOTO, TEXT, SCAN }
    static final int MAX_IMAGES=9, MAX_DOCUMENTS=4;
    final Kind kind;
    final String name;
    final int textBytes;
    final List<String> paths;
    long id; // Assigned by SQLite. Zero while importing.
    Attachment(Kind kind,String name,int textBytes,List<String> paths) {
        this.kind=kind; this.name=name; this.textBytes=textBytes;
        this.paths=Collections.unmodifiableList(new ArrayList<>(paths));
    }
    static Attachment photo(String path) {
        return new Attachment(Kind.PHOTO,"照片.jpg",0,Collections.singletonList(path));
    }
    int imageCount() { return kind==Kind.TEXT ? 0 : paths.size(); }
    static int images(List<Attachment> items) {
        int count=0;
        for (Attachment item:items) count+=item.imageCount();
        return count;
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
