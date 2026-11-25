package com.sunwin.deepseekharness;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

final class History extends SQLiteOpenHelper {
    static final class Chat {
        final long id; final String title, remote;
        Chat(Cursor c) { id=c.getLong(0); title=c.getString(1); remote=c.getString(2); }
    }
    static final class Message {
        final long id; final String role, text, state, error;
        final List<Attachment> attachments;
        Message(Cursor c,List<Attachment> attachments) {
            id=c.getLong(0); role=c.getString(1); text=c.getString(2); state=c.getString(3); error=c.getString(4);
            this.attachments=attachments;
        }
    }
    private final File filesDir;
    History(Context context) { super(context, "chats.db", null, 5); filesDir=context.getFilesDir(); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE chat(id INTEGER PRIMARY KEY, title TEXT NOT NULL, remote TEXT UNIQUE, updated INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE message(id INTEGER PRIMARY KEY, chat INTEGER NOT NULL REFERENCES chat(id), role TEXT NOT NULL, body TEXT NOT NULL, photo TEXT, state TEXT NOT NULL, error TEXT)");
        db.execSQL("CREATE INDEX chat_messages ON message(chat,id)");
        createMeter(db);
        createPhotoCleanup(db);
        createAttachments(db);
    }
    private static void createMeter(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE context_meter(chat INTEGER PRIMARY KEY REFERENCES chat(id) ON DELETE CASCADE, model TEXT NOT NULL, used INTEGER NOT NULL, capacity INTEGER NOT NULL)");
    }
    private static void createPhotoCleanup(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE photo_cleanup(photo TEXT PRIMARY KEY NOT NULL)");
    }
    private static void createAttachments(SQLiteDatabase db) {
        // A NULL message owns a compose draft. One scan can own several ordered JPEGs.
        db.execSQL("CREATE TABLE attachment(id INTEGER PRIMARY KEY, chat INTEGER NOT NULL REFERENCES chat(id) ON DELETE CASCADE, "+
                "message INTEGER REFERENCES message(id) ON DELETE CASCADE, kind TEXT NOT NULL, name TEXT NOT NULL, bytes INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX chat_attachments ON attachment(chat,message,id)");
        db.execSQL("CREATE TABLE attachment_file(attachment INTEGER NOT NULL REFERENCES attachment(id) ON DELETE CASCADE, "+
                "position INTEGER NOT NULL, path TEXT NOT NULL, PRIMARY KEY(attachment,position))");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE message ADD COLUMN error TEXT");
        if (oldVersion < 3) createMeter(db);
        if (oldVersion < 4) createPhotoCleanup(db);
        if (oldVersion < 5) {
            createAttachments(db);
            db.execSQL("INSERT INTO attachment(id,chat,message,kind,name,bytes) SELECT id,chat,id,'PHOTO','照片.jpg',0 FROM message WHERE photo IS NOT NULL");
            db.execSQL("INSERT INTO attachment_file SELECT id,0,photo FROM message WHERE photo IS NOT NULL");
            db.execSQL("UPDATE message SET photo=NULL WHERE photo IS NOT NULL");
        }
    }
    void meter(long id,String model,ContextUsage usage) {
        getWritableDatabase().execSQL("INSERT OR REPLACE INTO context_meter(chat,model,used,capacity) VALUES(?,?,?,?)",
                new Object[]{id,model,usage.used,usage.window});
    }
    ContextUsage meter(long id,String model) {
        try (Cursor c=getReadableDatabase().rawQuery("SELECT model,used,capacity FROM context_meter WHERE chat=?",args(id))) {
            if (c.moveToFirst()) {
                long capacity=model.isEmpty() || model.equals(c.getString(0)) ? c.getLong(2) : ContextUsage.defaultWindow(model);
                return new ContextUsage(c.getLong(1),capacity,false);
            }
        }
        try (Cursor c=getReadableDatabase().rawQuery(
                "SELECT SUM(length(CAST(body AS BLOB))+16) + (SELECT COALESCE(SUM(CASE WHEN a.kind='TEXT' THEN a.bytes+8 "+
                "ELSE 1032 END + CASE WHEN a.kind!='PHOTO' AND f.position=0 THEN length(CAST(a.name AS BLOB))+20 ELSE 0 END),0) "+
                "FROM attachment a JOIN attachment_file f ON f.attachment=a.id WHERE a.message IS NOT NULL AND a.chat=?) "+
                "FROM message WHERE chat=?",new String[]{Long.toString(id),Long.toString(id)})) {
            c.moveToFirst();
            return new ContextUsage(c.isNull(0) ? 0 : c.getLong(0)+1024,ContextUsage.defaultWindow(model),false);
        }
    }
    int recover() {
        ContentValues v = new ContentValues();
        v.put("state", "interrupted"); v.put("error", "应用进程已重启");
        return getWritableDatabase().update("message", v, "state='pending'", null);
    }
    long create() {
        ContentValues v = new ContentValues(); v.put("title","新对话"); v.put("updated",System.currentTimeMillis());
        return getWritableDatabase().insertOrThrow("chat",null,v);
    }
    List<Chat> list() {
        List<Chat> items = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,title,remote FROM chat ORDER BY updated DESC,id DESC",null)) {
            while (c.moveToNext()) items.add(new Chat(c));
        }
        return items;
    }
    Chat chat(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,title,remote FROM chat WHERE id=?",args(id))) {
            return c.moveToFirst() ? new Chat(c) : null;
        }
    }

    long delete(long id,long current,Diagnostics log) {
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            // Allocate the replacement while the old row still exists. SQLite can reuse row IDs.
            if (current==id) current=create();
            db.execSQL("INSERT OR IGNORE INTO photo_cleanup(photo) SELECT f.path FROM attachment_file f JOIN attachment a ON a.id=f.attachment WHERE a.chat=?",
                    new Object[]{id});
            db.delete("message","chat=?",args(id));
            db.delete("chat","id=?",args(id)); // context_meter cascades with its chat.
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        cleanDeletedPhotos(log);
        return current;
    }
    void recoverAttachmentFiles(Diagnostics log) {
        // Importers start after Application.onCreate. A killed import may have finished
        // writing a JPEG or text file just before recording the draft in SQLite.
        Set<String> candidates=new HashSet<>();
        for (String folder:new String[]{"photos","documents"}) {
            String[] names=new File(filesDir,folder).list((dir,name)->name.startsWith("attachment-"));
            if (names!=null) for (String name:names) candidates.add(folder+"/"+name);
        }
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            if (!candidates.isEmpty()) {
                try (Cursor c=db.rawQuery("SELECT DISTINCT path FROM attachment_file",null)) {
                    while (c.moveToNext()) candidates.remove(c.getString(0));
                }
                for (String path:candidates) db.execSQL("INSERT OR IGNORE INTO photo_cleanup VALUES(?)",new Object[]{path});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        cleanDeletedPhotos(log);
    }
    void cleanDeletedPhotos(Diagnostics log) {
        // A crash after the chat commit leaves the candidates here. Check surviving references
        // under a write lock before unlinking, including when replaying cleanup at startup.
        try {
            SQLiteDatabase db=getWritableDatabase();
            db.beginTransaction();
            try {
                List<String> pending=new ArrayList<>();
                try (Cursor c=db.rawQuery("SELECT photo FROM photo_cleanup",null)) {
                    while (c.moveToNext()) pending.add(c.getString(0));
                }
                if (!pending.isEmpty()) unlinkOrphans(db,pending,log);
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        } catch (SQLiteException | IOException | InvalidPathException e) { log.add(Diagnostics.Event.PHOTO_DELETE_FAILED); }
    }
    private void unlinkOrphans(SQLiteDatabase db,List<String> pending,Diagnostics log) throws IOException {
        Path base=filesDir.getCanonicalFile().toPath();
        Set<Path> kept=new HashSet<>();
        try (Cursor c=db.rawQuery("SELECT DISTINCT path FROM attachment_file",null)) {
            while (c.moveToNext()) kept.add(base.resolve(c.getString(0)).toFile().getCanonicalFile().toPath());
        }
        for (String reference:pending) {
            try {
                Path path=base.resolve(reference).toFile().getCanonicalFile().toPath();
                if ((path.startsWith(base.resolve("photos")) || path.startsWith(base.resolve("documents")))
                        && !kept.contains(path) && Files.isRegularFile(path))
                    Files.deleteIfExists(path);
                db.delete("photo_cleanup","photo=?",new String[]{reference});
            } catch (IOException | InvalidPathException e) { log.add(Diagnostics.Event.PHOTO_DELETE_FAILED); }
        }
    }
    void link(long id,String remote) {
        getWritableDatabase().execSQL("UPDATE chat SET remote=? WHERE id=?", new Object[]{remote,id});
    }
    // Consumes one logical attachment, leaving the cursor at the next item or message.
    private Attachment readAttachment(Cursor c,int column) {
        long id=c.getLong(column);
        Attachment.Kind kind=Attachment.Kind.valueOf(c.getString(column+1));
        String name=c.getString(column+2); int bytes=c.getInt(column+3);
        List<String> paths=new ArrayList<>();
        do { paths.add(c.getString(column+4)); } while (c.moveToNext() && !c.isNull(column) && c.getLong(column)==id);
        Attachment item=new Attachment(kind,name,bytes,paths); item.id=id;
        return item;
    }
    List<Attachment> draft(long chat) {
        List<Attachment> items=new ArrayList<>();
        try (Cursor c=getReadableDatabase().rawQuery("SELECT a.id,a.kind,a.name,a.bytes,f.path FROM attachment a "+
                "JOIN attachment_file f ON f.attachment=a.id WHERE a.chat=? AND a.message IS NULL ORDER BY a.id,f.position",args(chat))) {
            if (c.moveToFirst()) while (!c.isAfterLast()) items.add(readAttachment(c,0));
        }
        return items;
    }
    List<Message> messages(long id) {
        List<Message> items=new ArrayList<>();
        // One snapshot: a newly committed bubble and its thumbnails must appear together.
        try (Cursor c=getReadableDatabase().rawQuery("SELECT m.id,m.role,m.body,m.state,m.error,a.id,a.kind,a.name,a.bytes,f.path "+
                "FROM message m LEFT JOIN attachment a ON a.chat=m.chat AND a.message=m.id "+
                "LEFT JOIN attachment_file f ON f.attachment=a.id WHERE m.chat=? ORDER BY m.id,a.id,f.position",args(id))) {
            if (c.moveToFirst()) while (!c.isAfterLast()) {
                List<Attachment> files=new ArrayList<>();
                Message message=new Message(c,files); items.add(message);
                if (c.isNull(5)) c.moveToNext();
                else do { files.add(readAttachment(c,5)); } while (!c.isAfterLast() && c.getLong(0)==message.id);
            }
        }
        return items;
    }
    private static String[] args(long id) { return new String[]{Long.toString(id)}; }
    private void insertAttachment(SQLiteDatabase db,long chat,Long message,Attachment item) {
        ContentValues v=new ContentValues();
        v.put("chat",chat); v.put("message",message); v.put("kind",item.kind.name());
        v.put("name",item.name); v.put("bytes",item.textBytes);
        long id=db.insertOrThrow("attachment",null,v);
        for (int i=0;i<item.paths.size();i++)
            db.execSQL("INSERT INTO attachment_file VALUES(?,?,?)",new Object[]{id,i,item.paths.get(i)});
    }
    boolean addDraft(long chat,Attachment item) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            if (chat(chat)==null) return false;
            List<Attachment> existing=draft(chat);
            if (Attachment.images(existing)+item.imageCount()>Attachment.MAX_IMAGES ||
                    (item.kind!=Attachment.Kind.PHOTO && Attachment.documents(existing)>=Attachment.MAX_DOCUMENTS)) return false;
            insertAttachment(db,chat,null,item);
            db.setTransactionSuccessful(); return true;
        } finally { db.endTransaction(); }
    }
    void removeDraft(long chat,long id,Diagnostics log) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            db.execSQL("INSERT OR IGNORE INTO photo_cleanup SELECT path FROM attachment_file WHERE attachment IN "+
                    "(SELECT id FROM attachment WHERE id=? AND chat=? AND message IS NULL)",new Object[]{id,chat});
            db.delete("attachment","id=? AND chat=? AND message IS NULL",new String[]{Long.toString(id),Long.toString(chat)});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        cleanDeletedPhotos(log);
    }
    long begin(long chat,String text,List<Attachment> files) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            String title=text.replace('\n',' ').trim();
            if (title.isEmpty() && !files.isEmpty()) title=files.get(0).kind==Attachment.Kind.PHOTO ? "图片对话" : files.get(0).name;
            int end=title.offsetByCodePoints(0,Math.min(22,title.codePointCount(0,title.length())));
            db.execSQL("UPDATE chat SET updated=?, title=CASE WHEN EXISTS(SELECT 1 FROM message WHERE chat=?) THEN title ELSE ? END WHERE id=?",
                    new Object[]{System.currentTimeMillis(),chat,title.substring(0,end),chat});
            ContentValues v=new ContentValues();
            v.put("chat",chat); v.put("role","user"); v.put("body",text); v.put("state","done");
            long user=db.insertOrThrow("message",null,v);
            for (Attachment item:files) {
                if (item.id==0) insertAttachment(db,chat,user,item);
                else {
                    ContentValues moved=new ContentValues(); moved.put("message",user);
                    if (db.update("attachment",moved,"id=? AND chat=? AND message IS NULL",
                            new String[]{Long.toString(item.id),Long.toString(chat)})!=1) throw new SQLiteException("stale-draft");
                }
            }
            v.put("role","assistant"); v.put("body",""); v.put("state","pending");
            long id=db.insertOrThrow("message",null,v);
            db.setTransactionSuccessful(); return id;
        } finally { db.endTransaction(); }
    }
    void update(long id,String body,String state) {
        update(id,body,state,null);
    }
    void update(long id,String body,String state,String error) {
        getWritableDatabase().execSQL("UPDATE message SET body=?,state=?,error=? WHERE id=? AND state='pending'",new Object[]{body,state,error,id});
    }
}
