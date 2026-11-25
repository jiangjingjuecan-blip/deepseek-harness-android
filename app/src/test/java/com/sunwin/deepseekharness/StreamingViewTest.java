package com.sunwin.deepseekharness;

import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,application=HarnessApp.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class StreamingViewTest {
    private TextView find(View v,String text) {
        if (v instanceof TextView && text.contentEquals(((TextView)v).getText())) return (TextView)v;
        if (v instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)v;
            for (int i=0;i<group.getChildCount();i++) {
                TextView item=find(group.getChildAt(i),text); if (item!=null) return item;
            }
        }
        return null;
    }
    private boolean contains(View v,String text) { return find(v,text)!=null; }
    @Test public void historyIsImmediateAndOnlyTheNewRegeneratedVersionReveals() {
        HarnessApp app=(HarnessApp)RuntimeEnvironment.getApplication();
        History h=app.history; long chat=h.create(),old=h.begin(chat,"开场",Collections.emptyList());
        String original=String.join("",Collections.nCopies(50,"原文")),next=String.join("",Collections.nCopies(100,"新"));
        h.update(old,original,"done");
        app.getSharedPreferences("ui",0).edit().putLong("chat",chat).putBoolean("batteryAsked",true)
                .putBoolean("notificationAsked",true).commit();
        ActivityController<MainActivity> activity=Robolectric.buildActivity(MainActivity.class).setup();
        View root=activity.get().getWindow().getDecorView();
        assertTrue(contains(root,original));
        long regen=h.regenerate(chat,old);
        ChatEngine.Work w=new ChatEngine.Work(h.chat(chat),"deepseek-flash");
        w.row=w.localReply=regen; w.user=h.last(w.chat.branch,regen); app.chat.latest=w;
        app.changed(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        TextView body=find(root,"正在回复…"); assertNotNull(body);
        boolean[] partial={false};
        body.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            public void onTextChanged(CharSequence s,int start,int before,int count) {
                if (s.length()>0 && s.length()<next.length() && next.startsWith(s.toString())) partial[0]=true;
            }
            public void afterTextChanged(android.text.Editable e) { }
        });
        w.body=next; h.update(regen,next,"pending"); app.streamChanged();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(500,TimeUnit.MILLISECONDS);
        assertTrue("a received burst was drawn in prefixes",partial[0]); assertFalse(contains(root,original));
        h.update(regen,next,"done"); w.ended=true;
        h.switchVersion(chat,regen,-1); app.changed(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(contains(root,original));
        h.switchVersion(chat,old,1); app.changed(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(contains(root,next));
        activity.pause().stop().destroy();
        activity=Robolectric.buildActivity(MainActivity.class).setup();
        assertTrue(contains(activity.get().getWindow().getDecorView(),next)); activity.pause().stop().destroy();
    }
    @Test public void growthLeavesAnOlderViewportPinnedUntilExplicitJump() {
        HarnessApp app=(HarnessApp)RuntimeEnvironment.getApplication();
        ConversationScroll scroll=new ConversationScroll(app,48);
        View child=new View(app),button=new View(app); scroll.jumpButton(button);
        child.setMinimumHeight(1000); scroll.addView(child,new FrameLayout.LayoutParams(300,1000));
        int width=View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY);
        int height=View.MeasureSpec.makeMeasureSpec(200,View.MeasureSpec.EXACTLY);
        scroll.measure(width,height); scroll.layout(0,0,300,200);
        scroll.scrollTo(0,800); scroll.scrollTo(0,400);
        assertEquals("child height",1000,child.getHeight()); assertEquals("viewport",200,scroll.getHeight());
        assertEquals("offset",400,scroll.getScrollY()); assertEquals("jump affordance",View.VISIBLE,button.getVisibility());
        child.setMinimumHeight(1300); child.setLayoutParams(new FrameLayout.LayoutParams(300,1300));
        scroll.measure(width,height); scroll.layout(0,0,300,200);
        assertEquals(400,scroll.getScrollY());
        scroll.latest(); assertEquals(View.GONE,button.getVisibility());
    }
}
