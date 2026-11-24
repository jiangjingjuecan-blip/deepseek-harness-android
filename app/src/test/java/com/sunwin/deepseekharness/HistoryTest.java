package com.sunwin.deepseekharness;

import android.app.Application;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE, application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class HistoryTest {
    @Test public void restartRetainsSessionImageAndPartialReply() {
        Application ctx=RuntimeEnvironment.getApplication();
        History first=new History(ctx); long id=first.create();
        first.link(id,"dsh-session"); long answer=first.begin(id,"看图","photos/example.jpg");
        first.update(answer,"第一段","pending"); first.close();
        History second=new History(ctx); second.recover();
        List<History.Message> messages=second.messages(id);
        assertEquals("dsh-session",second.chat(id).remote); assertEquals("看图",second.list().get(0).title);
        assertEquals("photos/example.jpg",messages.get(0).photo);
        assertEquals("interrupted",messages.get(1).state); assertEquals("第一段",messages.get(1).text);
        long follow=second.begin(id,"继续",null); second.update(follow,"尾声","done"); second.recover();
        assertEquals(4,second.messages(id).size()); assertEquals("done",second.messages(id).get(3).state);
        long blank=second.create(); assertTrue(second.messages(blank).isEmpty()); second.close();
    }
}
