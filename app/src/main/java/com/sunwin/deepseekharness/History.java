package com.sunwin.deepseekharness;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

final class History extends SQLiteOpenHelper {
    static final class Chat {
        final long id, branch, number; final String title, remote; final boolean dirty;
        Chat(Cursor c) { id=c.getLong(0); title=c.getString(1); remote=c.getString(2); branch=c.getLong(3); dirty=c.getInt(4)!=0; number=c.getLong(5); }
    }
    static final class Branch {
        final long id, number;
        Branch(Cursor c) { id=c.getLong(0); number=c.getLong(1); }
        String label() { return number==1 ? "主线" : "分支 "+number; }
    }
    static final class Message {
        final long id; final String role, text, state, error;
        final List<Attachment> attachments;
        final long family, seq;
        final String session;
        final int version, versions, fileBytes, imageSlots;
        final boolean saved;
        final int missing; final boolean documents;
        Message(long id,String role,String text,List<Attachment> attachments) {
            this.id=id; this.role=role; this.text=text; this.attachments=attachments;
            state="assistant".equals(role)?"pending":"done"; error=session=null;
            family=id; seq=fileBytes=imageSlots=-1; version=versions=1; saved=false; missing=0; documents=false;
        }
        Message(Cursor c,List<Attachment> attachments) {
            id=c.getLong(0); role=c.getString(1); text=c.getString(2); state=c.getString(3); error=c.getString(4);
            family=c.getLong(5); version=c.getInt(6); versions=c.getInt(7); saved=c.getInt(8)!=0;
            session=c.getString(9); seq=c.getLong(10); fileBytes=c.getInt(11); imageSlots=c.getInt(12);
            this.attachments=attachments; missing=c.getInt(19); documents=c.getInt(20)!=0;
        }
    }
    private final File filesDir;
    History(Context context) { super(context, "chats.db", null, 8); filesDir=context.getFilesDir(); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE chat(id INTEGER PRIMARY KEY, title TEXT NOT NULL, remote TEXT UNIQUE, updated INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE message(id INTEGER PRIMARY KEY, chat INTEGER NOT NULL REFERENCES chat(id), role TEXT NOT NULL, body TEXT NOT NULL, photo TEXT, state TEXT NOT NULL, error TEXT)");
        db.execSQL("CREATE INDEX chat_messages ON message(chat,id)");
        createMeter(db);
        createPhotoCleanup(db);
        createAttachments(db);
        createBranches(db);
        createImports(db);
    }
    private static void createImports(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE message_import(message INTEGER NOT NULL REFERENCES message(id) ON DELETE CASCADE,"+
                "position INTEGER NOT NULL,uri TEXT NOT NULL,document INTEGER NOT NULL,PRIMARY KEY(message,position),UNIQUE(message,uri))");
    }
    private static void createMeter(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE context_meter(chat INTEGER PRIMARY KEY REFERENCES chat(id) ON DELETE CASCADE, model TEXT NOT NULL, used INTEGER NOT NULL, capacity INTEGER NOT NULL)");
    }
    private static void createPhotoCleanup(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE photo_cleanup(photo TEXT PRIMARY KEY NOT NULL)");
    }
    private static void createAttachments(SQLiteDatabase db) {
        // A NULL message owns a compose draft. Document bodies and their ordered images share the same owner.
        db.execSQL("CREATE TABLE attachment(id INTEGER PRIMARY KEY, chat INTEGER NOT NULL REFERENCES chat(id) ON DELETE CASCADE, "+
                "message INTEGER REFERENCES message(id) ON DELETE CASCADE, kind TEXT NOT NULL, name TEXT NOT NULL, bytes INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX chat_attachments ON attachment(chat,message,id)");
        db.execSQL("CREATE TABLE attachment_file(attachment INTEGER NOT NULL REFERENCES attachment(id) ON DELETE CASCADE, "+
                "position INTEGER NOT NULL, path TEXT NOT NULL, text_offset INTEGER, PRIMARY KEY(attachment,position))");
    }
    private static void createBranches(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE branch(id INTEGER PRIMARY KEY AUTOINCREMENT, chat INTEGER NOT NULL REFERENCES chat(id) ON DELETE CASCADE,"+
                "number INTEGER NOT NULL, draft TEXT NOT NULL DEFAULT '', selected INTEGER NOT NULL DEFAULT 0, remote TEXT UNIQUE, dirty INTEGER NOT NULL DEFAULT 0, UNIQUE(chat,number))");
        db.execSQL("CREATE UNIQUE INDEX selected_branch ON branch(chat) WHERE selected=1");
        db.execSQL("INSERT INTO branch(id,chat,number,selected,remote) SELECT id,id,1,1,remote FROM chat");
        db.execSQL("CREATE TABLE chat_session(remote TEXT PRIMARY KEY NOT NULL,chat INTEGER NOT NULL REFERENCES chat(id) ON DELETE CASCADE)");
        db.execSQL("INSERT INTO chat_session SELECT remote,id FROM chat WHERE remote IS NOT NULL");
        db.execSQL("UPDATE chat SET remote=NULL");
        // A message version owns its content and attachments. A line owns only ordered references.
        db.execSQL("ALTER TABLE message ADD COLUMN home INTEGER REFERENCES branch(id)");
        db.execSQL("ALTER TABLE message ADD COLUMN family INTEGER");
        db.execSQL("ALTER TABLE message ADD COLUMN anchor_remote TEXT");
        db.execSQL("ALTER TABLE message ADD COLUMN anchor_seq INTEGER");
        db.execSQL("ALTER TABLE message ADD COLUMN file_bytes INTEGER");
        db.execSQL("ALTER TABLE message ADD COLUMN image_slots INTEGER");
        db.execSQL("UPDATE message SET home=chat,family=id,anchor_remote=(SELECT b.remote FROM branch b WHERE b.id=message.chat)");
        db.execSQL("CREATE INDEX message_versions ON message(family,id)");
        db.execSQL("CREATE TABLE branch_message(branch INTEGER NOT NULL REFERENCES branch(id) ON DELETE CASCADE,"+
                "message INTEGER NOT NULL REFERENCES message(id) ON DELETE CASCADE,PRIMARY KEY(branch,message))");
        db.execSQL("INSERT INTO branch_message SELECT chat,id FROM message");
        db.execSQL("CREATE TABLE save_point(branch INTEGER NOT NULL,message INTEGER NOT NULL,PRIMARY KEY(branch,message),"+
                "FOREIGN KEY(branch,message) REFERENCES branch_message(branch,message) ON DELETE CASCADE)");
        db.execSQL("CREATE TABLE version_visit(message INTEGER PRIMARY KEY,branch INTEGER NOT NULL,"+
                "FOREIGN KEY(branch,message) REFERENCES branch_message(branch,message) ON DELETE CASCADE)");
        db.execSQL("ALTER TABLE attachment ADD COLUMN draft_branch INTEGER REFERENCES branch(id) ON DELETE CASCADE");
        db.execSQL("UPDATE attachment SET draft_branch=chat WHERE message IS NULL");
        db.execSQL("ALTER TABLE context_meter RENAME TO meter_v6");
        db.execSQL("CREATE TABLE context_meter(branch INTEGER PRIMARY KEY REFERENCES branch(id) ON DELETE CASCADE,"+
                "chat INTEGER NOT NULL REFERENCES chat(id) ON DELETE CASCADE,model TEXT NOT NULL,used INTEGER NOT NULL,capacity INTEGER NOT NULL)");
        db.execSQL("INSERT INTO context_meter SELECT chat,chat,model,used,capacity FROM meter_v6");
        db.execSQL("DROP TABLE meter_v6");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE message ADD COLUMN error TEXT");
        if (oldVersion < 3) createMeter(db);
        if (oldVersion < 4) createPhotoCleanup(db);
        if (oldVersion < 5) {
            createAttachments(db);
            db.execSQL("INSERT INTO attachment(id,chat,message,kind,name,bytes) SELECT id,chat,id,'PHOTO','照片.jpg',0 FROM message WHERE photo IS NOT NULL");
            db.execSQL("INSERT INTO attachment_file(attachment,position,path) SELECT id,0,photo FROM message WHERE photo IS NOT NULL");
            db.execSQL("UPDATE message SET photo=NULL WHERE photo IS NOT NULL");
        } else if (oldVersion==5) db.execSQL("ALTER TABLE attachment_file ADD COLUMN text_offset INTEGER");
        if (oldVersion<7) createBranches(db);
        if (oldVersion<8) createImports(db);
    }
    void meter(long id,String model,ContextUsage usage) {
        meterBranch(chat(id).branch,model,usage);
    }
    void meterBranch(long branch,String model,ContextUsage usage) {
        getWritableDatabase().execSQL("INSERT OR REPLACE INTO context_meter(branch,chat,model,used,capacity) SELECT id,chat,?,?,? FROM branch WHERE id=?",
                new Object[]{model,usage.used,usage.window,branch});
    }
    ContextUsage meter(long id,String model) {
        long branch=chat(id).branch;
        try (Cursor c=getReadableDatabase().rawQuery("SELECT model,used,capacity FROM context_meter WHERE branch=?",args(branch))) {
            if (c.moveToFirst()) {
                long capacity=model.isEmpty() || model.equals(c.getString(0)) ? c.getLong(2) : ContextUsage.defaultWindow(model);
                return new ContextUsage(c.getLong(1),capacity,false);
            }
        }
        try (Cursor c=getReadableDatabase().rawQuery(
                "SELECT SUM(length(CAST(body AS BLOB))+16) + (SELECT COALESCE(SUM(CASE WHEN a.kind='TEXT' AND f.position=0 THEN a.bytes+8 "+
                "ELSE 1032 END + CASE WHEN a.kind!='PHOTO' AND f.position=0 THEN length(CAST(a.name AS BLOB))+20 ELSE 0 END),0) "+
                "FROM branch_message bm JOIN attachment a ON a.message=bm.message JOIN attachment_file f ON f.attachment=a.id WHERE bm.branch=?) "+
                "FROM branch_message bm JOIN message m ON m.id=bm.message WHERE bm.branch=?",args(branch,branch))) {
            c.moveToFirst();
            return new ContextUsage(c.isNull(0) ? 0 : c.getLong(0)+1024,ContextUsage.defaultWindow(model),false);
        }
    }
    int recover() {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            db.execSQL("UPDATE branch SET dirty=1 WHERE id IN (SELECT home FROM message WHERE state='pending')");
            ContentValues v = new ContentValues();
            v.put("state", "interrupted"); v.put("error", "应用进程已重启");
            int count=db.update("message", v, "state='pending'", null);
            db.setTransactionSuccessful(); return count;
        } finally { db.endTransaction(); }
    }
    long create() {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            ContentValues v=new ContentValues(); v.put("title","新对话"); v.put("updated",System.currentTimeMillis());
            long chat=db.insertOrThrow("chat",null,v);
            db.execSQL("INSERT INTO branch(chat,number,selected) VALUES(?,1,1)",new Object[]{chat});
            db.setTransactionSuccessful(); return chat;
        } finally { db.endTransaction(); }
    }
    private static final String CHAT_BRANCH="SELECT c.id,c.title,b.remote,b.id,b.dirty,b.number FROM chat c JOIN branch b ON b.chat=c.id ";
    private static final String CHATS=CHAT_BRANCH+"AND b.selected=1 ";
    Chat branchChat(long branch) {
        try (Cursor c=getReadableDatabase().rawQuery(CHAT_BRANCH+"WHERE b.id=?",args(branch))) {
            if (!c.moveToFirst()) throw new SQLiteException("missing-branch");
            return new Chat(c);
        }
    }
    List<Chat> list() {
        List<Chat> items = new ArrayList<>();
        try (Cursor c=getReadableDatabase().rawQuery(CHATS+"ORDER BY c.updated DESC,c.id DESC",null)) {
            while (c.moveToNext()) items.add(new Chat(c));
        }
        return items;
    }
    Chat chat(long id) {
        try (Cursor c=getReadableDatabase().rawQuery(CHATS+"WHERE c.id=?",args(id))) {
            return c.moveToFirst() ? new Chat(c) : null;
        }
    }
    List<Branch> branches(long chat) {
        List<Branch> items=new ArrayList<>();
        try (Cursor c=getReadableDatabase().rawQuery("SELECT id,number FROM branch WHERE chat=? ORDER BY number",args(chat))) {
            while (c.moveToNext()) items.add(new Branch(c));
        }
        return items;
    }
    String textDraft(long branch) {
        try (Cursor c=getReadableDatabase().rawQuery("SELECT draft FROM branch WHERE id=?",args(branch))) {
            return c.moveToFirst() ? c.getString(0) : "";
        }
    }
    void textDraft(long branch,String text) {
        getWritableDatabase().execSQL("UPDATE branch SET draft=? WHERE id=?",new Object[]{text,branch});
    }
    void selectBranch(long chat,long branch) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            db.execSQL("UPDATE branch SET selected=0 WHERE chat=? AND selected=1",new Object[]{chat});
            ContentValues v=new ContentValues(); v.put("selected",1);
            if (db.update("branch",v,"chat=? AND id=?",args(chat,branch))!=1) throw new SQLiteException("missing-branch");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    List<String> sessions(long chat) {
        List<String> items=new ArrayList<>();
        try (Cursor c=getReadableDatabase().rawQuery("SELECT remote FROM chat_session WHERE chat=?",args(chat))) {
            while (c.moveToNext()) items.add(c.getString(0));
        }
        return items;
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
        linkBranch(chat(id).branch,remote);
    }
    void trackSession(long chat,String remote) {
        getWritableDatabase().execSQL("INSERT OR IGNORE INTO chat_session(remote,chat) VALUES(?,?)",new Object[]{remote,chat});
    }
    void linkBranch(long branch,String remote) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            db.execSQL("INSERT OR IGNORE INTO chat_session SELECT ?,chat FROM branch WHERE id=?",new Object[]{remote,branch});
            db.execSQL("UPDATE branch SET remote=?,dirty=0 WHERE id=?",new Object[]{remote,branch});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    void dirty(long branch) {
        getWritableDatabase().execSQL("UPDATE branch SET dirty=1 WHERE id=?",new Object[]{branch});
    }
    void anchor(long message,String session,long seq) {
        if (seq>=0) getWritableDatabase().execSQL("UPDATE message SET anchor_remote=?,anchor_seq=? WHERE id=? AND anchor_seq IS NULL",
                new Object[]{session,seq,message});
    }
    void allowance(long user,int bytes,int slots) {
        getWritableDatabase().execSQL("UPDATE message SET file_bytes=?,image_slots=? WHERE id=? AND file_bytes IS NULL",new Object[]{bytes,slots,user});
    }
    // Consumes one logical attachment, leaving the cursor at the next item or message.
    private Attachment readAttachment(Cursor c,int column) {
        long id=c.getLong(column);
        Attachment.Kind kind=Attachment.Kind.valueOf(c.getString(column+1));
        String name=c.getString(column+2); int bytes=c.getInt(column+3);
        List<String> paths=new ArrayList<>();
        List<Integer> offsets=new ArrayList<>();
        do {
            paths.add(c.getString(column+4));
            if (!c.isNull(column+5)) offsets.add(c.getInt(column+5));
        } while (c.moveToNext() && !c.isNull(column) && c.getLong(column)==id);
        Attachment item=new Attachment(kind,name,bytes,paths,offsets); item.id=id;
        return item;
    }
    List<Attachment> draft(long chat) {
        Chat current=chat(chat);
        return current==null ? Collections.emptyList() : branchDraft(current.branch);
    }
    List<Attachment> branchDraft(long branch) {
        List<Attachment> items=new ArrayList<>();
        try (Cursor c=getReadableDatabase().rawQuery("SELECT a.id,a.kind,a.name,a.bytes,f.path,f.text_offset FROM attachment a "+
                "JOIN attachment_file f ON f.attachment=a.id WHERE a.draft_branch=? AND a.message IS NULL ORDER BY a.id,f.position",args(branch))) {
            if (c.moveToFirst()) while (!c.isAfterLast()) items.add(readAttachment(c,0));
        }
        return items;
    }
    List<Message> messages(long id) {
        Chat current=chat(id);
        return current==null ? Collections.emptyList() : line(current.branch,0,Long.MAX_VALUE);
    }
    List<Message> line(long branch,long from,long through) {
        List<Message> items=new ArrayList<>();
        try (Cursor c=getReadableDatabase().rawQuery("SELECT m.id,m.role,m.body,m.state,m.error,m.family,"+
                "(SELECT COUNT(*) FROM message v WHERE v.family=m.family AND v.id<=m.id),"+
                "(SELECT COUNT(*) FROM message v WHERE v.family=m.family),s.message IS NOT NULL,m.anchor_remote,COALESCE(m.anchor_seq,-1),"+
                "COALESCE(m.file_bytes,-1),COALESCE(m.image_slots,-1),a.id,a.kind,a.name,a.bytes,f.path,f.text_offset,"+
                "(SELECT COUNT(*) FROM message_import i WHERE i.message=m.id),(SELECT MAX(document) FROM message_import i WHERE i.message=m.id) "+
                "FROM branch_message b JOIN message m ON m.id=b.message LEFT JOIN save_point s ON s.branch=b.branch AND s.message=m.id "+
                "LEFT JOIN attachment a ON a.message=m.id LEFT JOIN attachment_file f ON f.attachment=a.id "+
                "WHERE b.branch=? AND b.message BETWEEN ? AND ? ORDER BY b.message,a.id,f.position",args(branch,from,through))) {
            if (c.moveToFirst()) while (!c.isAfterLast()) {
                List<Attachment> files=new ArrayList<>();
                Message message=new Message(c,files); items.add(message);
                if (c.isNull(13)) c.moveToNext();
                else do { files.add(readAttachment(c,13)); } while (!c.isAfterLast() && c.getLong(0)==message.id);
            }
        }
        return items;
    }
    Message at(long branch,long id) {
        List<Message> row=line(branch,id,id);
        if (row.isEmpty()) throw new SQLiteException("missing-message");
        return row.get(0);
    }
    private static String[] args(long... ids) {
        String[] result=new String[ids.length];
        for (int i=0;i<ids.length;i++) result[i]=Long.toString(ids[i]);
        return result;
    }
    private long insertAttachment(SQLiteDatabase db,long chat,long branch,Long message,Attachment item) {
        ContentValues v=new ContentValues();
        v.put("chat",chat); v.put("message",message); if (message==null) v.put("draft_branch",branch); v.put("kind",item.kind.name());
        v.put("name",item.name); v.put("bytes",item.textBytes);
        long id=db.insertOrThrow("attachment",null,v);
        for (int i=0;i<item.paths.size();i++)
            db.execSQL("INSERT INTO attachment_file(attachment,position,path,text_offset) VALUES(?,?,?,?)",
                    new Object[]{id,i,item.paths.get(i),item.kind==Attachment.Kind.TEXT && i>0 ? item.imageOffsets.get(i-1) : null});
        return id;
    }
    boolean addDraft(long chat,Attachment item) {
        Chat current=chat(chat);
        return current!=null && addDraft(chat,current.branch,item);
    }
    boolean addDraft(long chat,long branch,Attachment item) {
        return addDraftFile(chat,branch,item)!=0;
    }
    long addDraftFile(long chat,long branch,Attachment item) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            if (android.database.DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM branch WHERE id=? AND chat=?",args(branch,chat))==0) return 0;
            List<Attachment> existing=branchDraft(branch);
            if ((item.kind==Attachment.Kind.PHOTO && Attachment.photos(existing)>=Attachment.MAX_PHOTOS) ||
                    (item.kind!=Attachment.Kind.PHOTO && Attachment.documents(existing)>=Attachment.MAX_DOCUMENTS)) return 0;
            long id=insertAttachment(db,chat,branch,null,item);
            db.setTransactionSuccessful(); return id;
        } finally { db.endTransaction(); }
    }
    void recordImports(long user,DraftImporter.Batch batch) {
        int position=0;
        for (android.net.Uri uri:batch.remaining) getWritableDatabase().execSQL("INSERT INTO message_import VALUES(?,?,?,?)",
                new Object[]{user,position++,uri.toString(),batch.document?1:0});
    }
    DraftImporter.Batch pendingImports(long chat,long branch,long user) {
        List<android.net.Uri> uris=new ArrayList<>(); boolean document=false;
        try (Cursor c=getReadableDatabase().rawQuery("SELECT uri,document FROM message_import WHERE message=? ORDER BY position",args(user))) {
            while (c.moveToNext()) { uris.add(android.net.Uri.parse(c.getString(0))); document=c.getInt(1)!=0; }
        }
        return uris.isEmpty()?null:new DraftImporter.Batch(chat,branch,uris,document,null);
    }
    boolean needsUri(android.net.Uri uri) {
        return android.database.DatabaseUtils.longForQuery(getReadableDatabase(),"SELECT EXISTS(SELECT 1 FROM message_import WHERE uri=?)",
                new String[]{uri.toString()})!=0;
    }
    List<android.net.Uri> importSources(long chat) {
        List<android.net.Uri> result=new ArrayList<>();
        try (Cursor c=getReadableDatabase().rawQuery("SELECT i.uri FROM message_import i JOIN message m ON i.message=m.id WHERE m.chat=?",args(chat))) {
            while (c.moveToNext()) result.add(android.net.Uri.parse(c.getString(0)));
        }
        return result;
    }
    long finishImport(DraftImporter.Batch batch,android.net.Uri uri,Attachment item) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            if (db.delete("message_import","message=? AND uri=? AND EXISTS(SELECT 1 FROM branch_message WHERE branch=? AND message=?)",
                    new String[]{Long.toString(batch.user),uri.toString(),Long.toString(batch.branch),Long.toString(batch.user)})!=1) return 0;
            long id=item==null?0:insertAttachment(db,batch.chat,batch.branch,batch.user,item);
            db.setTransactionSuccessful(); return id;
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
        return begin(chat,chat(chat).branch,text,files,"");
    }
    long begin(long chat,long branch,String text,List<Attachment> files,String pendingTitle) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            String title=text.replace('\n',' ').trim();
            if (title.isEmpty() && !files.isEmpty()) title=files.get(0).kind==Attachment.Kind.PHOTO ? "图片对话" : files.get(0).name;
            if (title.isEmpty()) title=pendingTitle;
            int end=title.offsetByCodePoints(0,Math.min(22,title.codePointCount(0,title.length())));
            db.execSQL("UPDATE chat SET updated=?, title=CASE WHEN EXISTS(SELECT 1 FROM message WHERE chat=?) THEN title ELSE ? END WHERE id=?",
                    new Object[]{System.currentTimeMillis(),chat,title.substring(0,end),chat});
            long user=append(db,chat,branch,"user",text,"done",0);
            for (Attachment item:files) {
                if (item.id==0) insertAttachment(db,chat,branch,user,item);
                else {
                    ContentValues moved=new ContentValues(); moved.put("message",user); moved.putNull("draft_branch");
                    if (db.update("attachment",moved,"id=? AND draft_branch=? AND message IS NULL",args(item.id,branch))!=1)
                        throw new SQLiteException("stale-draft");
                }
            }
            long id=append(db,chat,branch,"assistant","","pending",0);
            db.setTransactionSuccessful(); return id;
        } finally { db.endTransaction(); }
    }
    private long append(SQLiteDatabase db,long chat,long branch,String role,String text,String state,long family) {
        ContentValues v=new ContentValues();
        v.put("chat",chat); v.put("home",branch); v.put("role",role); v.put("body",text); v.put("state",state);
        long id=db.insertOrThrow("message",null,v);
        db.execSQL("UPDATE message SET family=? WHERE id=?",new Object[]{family==0?id:family,id});
        db.execSQL("INSERT INTO branch_message VALUES(?,?)",new Object[]{branch,id});
        return id;
    }
    long last(long branch,long before) {
        return android.database.DatabaseUtils.longForQuery(getReadableDatabase(),
                "SELECT COALESCE(MAX(message),0) FROM branch_message WHERE branch=? AND message<?",args(branch,before));
    }
    String diceBefore(long branch,long before) {
        StringBuilder rolls=new StringBuilder();
        try (Cursor c=getReadableDatabase().rawQuery("SELECT m.body FROM branch_message b JOIN message m ON m.id=b.message "+
                "WHERE b.branch=? AND b.message<? AND b.message>COALESCE((SELECT MAX(p.message) FROM branch_message p JOIN message q ON q.id=p.message "+
                "WHERE p.branch=b.branch AND p.message<? AND q.role!='dice'),0) ORDER BY b.message",args(branch,before,before))) {
            while (c.moveToNext()) { if (rolls.length()>0) rolls.append('\n'); rolls.append(c.getString(0)); }
        }
        return rolls.toString();
    }
    String promptText(long branch,Message user) {
        String rolls=diceBefore(branch,user.id);
        if (rolls.isEmpty() || user.text.isEmpty()) return rolls+user.text;
        return rolls+"\n"+user.text;
    }
    long roll(long chat,Dice result) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            long id=append(db,chat,chat(chat).branch,"dice",result.block(),"done",0);
            db.execSQL("UPDATE chat SET updated=?,title=CASE WHEN title='新对话' THEN '骰子' ELSE title END WHERE id=?",
                    new Object[]{System.currentTimeMillis(),chat});
            db.setTransactionSuccessful(); return id;
        } finally { db.endTransaction(); }
    }
    void bookmark(long branch,long id,boolean saved) {
        SQLiteDatabase db=getWritableDatabase();
        if (saved) db.execSQL("INSERT OR IGNORE INTO save_point VALUES(?,?)",new Object[]{branch,id});
        else db.delete("save_point","branch=? AND message=?",args(branch,id));
    }
    // Shared prefix entries are immutable once complete. A changed message always gets a fresh row.
    private long fork(SQLiteDatabase db,Chat source,long through) {
        if (android.database.DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM branch_message b JOIN message m ON m.id=b.message "+
                "WHERE b.branch=? AND b.message<=? AND m.state='pending'",args(source.branch,through))!=0) throw new SQLiteException("pending-prefix");
        db.execSQL("INSERT INTO branch(chat,number) SELECT ?,MAX(number)+1 FROM branch WHERE chat=?",new Object[]{source.id,source.id});
        long branch=android.database.DatabaseUtils.longForQuery(db,"SELECT last_insert_rowid()",null);
        db.execSQL("INSERT INTO branch_message SELECT ?,message FROM branch_message WHERE branch=? AND message<=?",new Object[]{branch,source.branch,through});
        db.execSQL("INSERT INTO save_point SELECT ?,message FROM save_point WHERE branch=? AND message<=?",new Object[]{branch,source.branch,through});
        selectBranch(source.id,branch);
        return branch;
    }
    long fork(long chat,long after) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            Chat source=chat(chat); at(source.branch,after);
            long branch=fork(db,source,after);
            db.setTransactionSuccessful(); return branch;
        } finally { db.endTransaction(); }
    }
    private void rememberVersion(long branch,long message) {
        getWritableDatabase().execSQL("INSERT OR REPLACE INTO version_visit(message,branch) VALUES(?,?)",new Object[]{message,branch});
    }
    long edit(long chat,long message,String text) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            Chat source=chat(chat); Message old=at(source.branch,message);
            if ("dice".equals(old.role) || (text.trim().isEmpty() && old.attachments.isEmpty())) throw new IllegalArgumentException("empty-edit");
            rememberVersion(source.branch,message);
            long branch=fork(db,source,message-1);
            long id=append(db,chat,branch,old.role,text,"done",old.family);
            for (Attachment file:old.attachments) insertAttachment(db,chat,branch,id,file);
            if (old.missing>0) db.execSQL("INSERT INTO message_import SELECT ?,position,uri,document FROM message_import WHERE message=?",
                    new Object[]{id,old.id});
            db.setTransactionSuccessful(); return id;
        } finally { db.endTransaction(); }
    }
    long regenerate(long chat,long answer) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            Chat source=chat(chat); Message old=at(source.branch,answer);
            long latest=android.database.DatabaseUtils.longForQuery(db,"SELECT COALESCE(MAX(b.message),0) FROM branch_message b "+
                    "JOIN message m ON m.id=b.message WHERE b.branch=? AND m.role='assistant'",args(source.branch));
            if (answer!=latest || !"user".equals(at(source.branch,last(source.branch,answer)).role)) throw new SQLiteException("stale-reply");
            rememberVersion(source.branch,answer);
            long branch=fork(db,source,answer-1);
            long id=append(db,chat,branch,"assistant","","pending",old.family);
            db.setTransactionSuccessful(); return id;
        } finally { db.endTransaction(); }
    }
    long answerUser(long chat,long user) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            Chat source=chat(chat);
            long id=answerOnBranch(chat,source.branch,user);
            db.setTransactionSuccessful(); return id;
        } finally { db.endTransaction(); }
    }
    long answerOnBranch(long chat,long branch,long user) {
        if (last(branch,Long.MAX_VALUE)!=user || !"user".equals(at(branch,user).role)) throw new SQLiteException("stale-user");
        return append(getWritableDatabase(),chat,branch,"assistant","","pending",0);
    }
    long retry(long chat,long user,long answer,boolean unsaved) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            Chat source=chat(chat); Message old=at(source.branch,answer);
            if (!("interrupted".equals(old.state) || unsaved && "pending".equals(old.state)) || !"assistant".equals(old.role) || last(source.branch,answer)!=user)
                throw new SQLiteException("stale-retry");
            long owners=android.database.DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM branch_message WHERE message=?",args(answer));
            if (owners==1 && last(source.branch,Long.MAX_VALUE)==answer) {
                db.execSQL("UPDATE message SET body='',state='pending',error=NULL,anchor_remote=NULL,anchor_seq=NULL WHERE id=?",args(answer));
            } else {
                rememberVersion(source.branch,answer);
                long branch=fork(db,source,user);
                answer=append(db,chat,branch,"assistant","","pending",old.family);
            }
            db.setTransactionSuccessful(); return answer;
        } finally { db.endTransaction(); }
    }
    long switchVersion(long chat,long message,int direction) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            Chat source=chat(chat); Message old=at(source.branch,message);
            String order=direction<0 ? " AND m.id<? ORDER BY m.id DESC" : " AND m.id>? ORDER BY m.id";
            try (Cursor c=db.rawQuery("SELECT COALESCE(v.branch,m.home),m.id FROM message m LEFT JOIN version_visit v ON v.message=m.id "+
                    "WHERE m.family=?"+order+" LIMIT 1",args(old.family,message))) {
                if (!c.moveToFirst()) return message;
                rememberVersion(source.branch,message); selectBranch(chat,c.getLong(0));
                db.setTransactionSuccessful(); return c.getLong(1);
            }
        } finally { db.endTransaction(); }
    }
    void update(long id,String body,String state) {
        update(id,body,state,null);
    }
    void update(long id,String body,String state,String error) {
        getWritableDatabase().execSQL("UPDATE message SET body=?,state=?,error=? WHERE id=? AND state='pending'",new Object[]{body,state,error,id});
    }
}
