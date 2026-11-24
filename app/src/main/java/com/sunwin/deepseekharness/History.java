package com.sunwin.deepseekharness;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

final class History extends SQLiteOpenHelper {
    static final class Chat {
        final long id; final String title, remote;
        Chat(Cursor c) { id=c.getLong(0); title=c.getString(1); remote=c.getString(2); }
    }
    static final class Message {
        final long id; final String role, text, photo, state;
        Message(Cursor c) { id=c.getLong(0); role=c.getString(1); text=c.getString(2); photo=c.getString(3); state=c.getString(4); }
    }
    History(Context context) { super(context, "chats.db", null, 1); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE chat(id INTEGER PRIMARY KEY, title TEXT NOT NULL, remote TEXT UNIQUE, updated INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE message(id INTEGER PRIMARY KEY, chat INTEGER NOT NULL REFERENCES chat(id), role TEXT NOT NULL, body TEXT NOT NULL, photo TEXT, state TEXT NOT NULL)");
        db.execSQL("CREATE INDEX chat_messages ON message(chat,id)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) { throw new IllegalStateException(); }
    void recover() { getWritableDatabase().execSQL("UPDATE message SET state='interrupted' WHERE state='pending'"); }
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
    void link(long id,String remote) {
        getWritableDatabase().execSQL("UPDATE chat SET remote=? WHERE id=?", new Object[]{remote,id});
    }
    List<Message> messages(long id) {
        List<Message> items = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,role,body,photo,state FROM message WHERE chat=? ORDER BY id",args(id))) {
            while (c.moveToNext()) items.add(new Message(c));
        }
        return items;
    }
    private static String[] args(long id) { return new String[]{Long.toString(id)}; }
    long begin(long chat,String text,String photo) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            String title = text.isEmpty() ? "图片对话" : text.replace('\n',' ').trim();
            int end = title.offsetByCodePoints(0,Math.min(22,title.codePointCount(0,title.length())));
            db.execSQL("UPDATE chat SET updated=?, title=CASE WHEN EXISTS(SELECT 1 FROM message WHERE chat=?) THEN title ELSE ? END WHERE id=?",
                    new Object[]{System.currentTimeMillis(),chat,title.substring(0,end),chat});
            ContentValues v = new ContentValues();
            v.put("chat",chat); v.put("role","user"); v.put("body",text); v.put("photo",photo); v.put("state","done");
            db.insertOrThrow("message",null,v);
            v.put("role","assistant"); v.put("body",""); v.putNull("photo"); v.put("state","pending");
            long id = db.insertOrThrow("message",null,v);
            db.setTransactionSuccessful(); return id;
        } finally { db.endTransaction(); }
    }
    void update(long id,String body,String state) {
        getWritableDatabase().execSQL("UPDATE message SET body=?,state=? WHERE id=? AND state='pending'",new Object[]{body,state,id});
    }
}
