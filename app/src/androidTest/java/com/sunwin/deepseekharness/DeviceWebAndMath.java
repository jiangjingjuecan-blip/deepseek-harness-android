package com.sunwin.deepseekharness;

import android.app.Instrumentation;
import android.os.SystemClock;
import android.text.Spanned;
import android.view.*;
import android.view.inspector.WindowInspector;
import android.widget.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Exercises web search toggle, live searching status chip, citations, AI titles, and Markdown+LaTeX rendering on device. */
final class DeviceWebAndMath {
    private final Instrumentation test;
    private final MainActivity activity;
    private final HarnessApp app;
    private final int port;
    String stage="web-ui";

    DeviceWebAndMath(Instrumentation test,MainActivity activity,int port) {
        this.test=test; this.activity=activity; this.port=port;
        app=(HarnessApp)activity.getApplication();
    }

    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    private <T> T ui(Callable<T> call) throws Exception {
        FutureTask<T> task=new FutureTask<>(call); test.runOnMainSync(task); return task.get();
    }
    private View root() { return activity.getWindow().getDecorView(); }
    private EditText input() { return (EditText)DeviceSendBench.find(root(),null,false); }
    private View control(String label) { return DeviceSendBench.find(root(),label,false); }
    private void await(String label,BooleanSupplier condition) throws Exception {
        stage=label;
        long until=SystemClock.elapsedRealtime()+45000;
        while (!ui(condition::getAsBoolean)) {
            if (SystemClock.elapsedRealtime()>until) throw new AssertionError(label);
            SystemClock.sleep(35);
        }
    }
    private void click(String label) throws Exception {
        await("click-"+label,()->{
            List<View> windows=WindowInspector.getGlobalWindowViews();
            for (int i=windows.size()-1;i>=0;i--) {
                View v=DeviceSendBench.find(windows.get(i),label,false);
                if (v==null || !v.isShown()) continue;
                while (!v.isClickable() && v.getParent() instanceof View) {
                    if (v.getParent() instanceof ListView) {
                        ListView list=(ListView)v.getParent(); int at=list.getPositionForView(v);
                        return list.performItemClick(v,at,list.getItemIdAtPosition(at));
                    }
                    v=(View)v.getParent();
                }
                return v.performClick();
            }
            return false;
        });
    }
    private void advance() throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/fixture-next").openConnection(Proxy.NO_PROXY);
        try { check(c.getResponseCode()==200); } finally { c.disconnect(); }
    }
    private void send(String text) throws Exception {
        ui(()->{ input().setText(text); return control("发送").performClick(); });
    }

    void run() throws Exception {
        // Use instant reveal so multi-block math & search verification is crisp and deterministic.
        ui(()->{ activity.getSharedPreferences("ui",0).edit().putInt(Typewriter.PREF,0).commit(); return true; });

        // 1. Composer web search globe toggle defaults ON.
        await("web-toggle-default-on",()->{
            View toggle=control("联网搜索");
            return toggle!=null && toggle.isShown() && toggle.isSelected();
        });

        // 2. Turn 1: Web search ON -> live status chip appears, then collapses; cited source chips & AI title appear.
        send("冰岛人口");
        await("searching-chip",()->{
            View chip=DeviceSendBench.find(root(),"搜索 Iceland population 2025",true);
            return chip!=null && chip.isShown();
        });
        advance();
        await("web-search-done",()->!app.chat.busy() && control("来源：华尔街见闻")!=null && control("来源：华经情报网")!=null);
        await("searching-chip-collapsed",()->{
            View chip=DeviceSendBench.find(root(),"搜索 Iceland population 2025",true);
            return chip==null || !chip.isShown();
        });
        await("ai-title-top-bar",()->control("冰岛人口统计")!=null && control("冰岛人口统计").isShown());

        // 3. Turn 2: Toggle web search OFF -> URL reading still works and persists signature.
        click("联网搜索");
        await("web-toggle-off",()->{
            View toggle=control("联网搜索");
            return toggle!=null && !toggle.isSelected() && !app.webSearchEnabled();
        });
        send("总结 https://example.com/post");
        await("read-url-done",()->!app.chat.busy() && control("来源：Example Post")!=null);
        long firstChat=activity.getSharedPreferences("ui",0).getLong("chat",0);
        List<History.Message> firstRows=app.history.messages(firstChat);
        check(firstRows.size()==4 && firstRows.get(3).webBlocks!=null && firstRows.get(3).webBlocks.contains("sig-example-post-v1"));

        // Restore toggle back to default ON.
        click("联网搜索");
        await("web-toggle-restored",()->control("联网搜索").isSelected() && app.webSearchEnabled());

        // 4. Open a fresh chat for the Markdown + LaTeX math smoke & screenshot.
        click("历史对话");
        await("history-ai-title",()->control("冰岛人口统计")!=null && control("冰岛人口统计").isShown());
        click("新建对话");
        send("公式展示");
        await("math-done",()->!app.chat.busy() && control("五次分式积分")!=null);

        // Verify display math HorizontalScrollViews, inline MathSpans, currency preserved, table, code block, and copy source.
        stage="verify-math-views";
        ui(()->{
            ScrollView scroll=DeviceSendBench.threadScroll(root());
            int[] stats=new int[5]; // [displayMathViews, totalMathSpans, tables, codeCards, rawCopyOk]
            inspectMath(scroll,stats);
            check(stats[0]>=2); // two $$ display equations
            check(stats[1]>=5); // display + inline + table math spans
            check(stats[2]>=1); // TableLayout
            check(stats[3]>=1); // monospace code block
            check(stats[4]>=1); // original $$ source preserved for copy
            // Currency "$5 and $10" stays intact as plain text (not turned into MathSpan)
            check(hasCurrencyOutsideMath(scroll,"$5 and $10"));
            return true;
        });

        // Verify history reload renders the math chat immediately and keeps both AI titles.
        click("历史对话");
        await("history-both-titles",()->control("五次分式积分")!=null && control("冰岛人口统计")!=null);
        click("五次分式积分");
        await("math-reopened",()->{
            ScrollView scroll=DeviceSendBench.threadScroll(root());
            int[] stats=new int[5];
            inspectMath(scroll,stats);
            return stats[0]>=2 && stats[1]>=5;
        });
        // Scroll to top of the math answer so the screenshot shows heading, both equations, table & code clearly.
        ui(()->{
            ScrollView scroll=DeviceSendBench.threadScroll(root());
            if (scroll!=null) scroll.scrollTo(0,0);
            return true;
        });
        test.waitForIdleSync();
        // Restore default reveal speed for subsequent tests.
        ui(()->{ activity.getSharedPreferences("ui",0).edit().remove(Typewriter.PREF).commit(); return true; });
    }

    private static void inspectMath(View v,int[] stats) {
        if (!v.isShown()) return;
        if (v instanceof TableLayout) stats[2]++;
        if (v instanceof TextView) {
            TextView tv=(TextView)v;
            if (tv.getTypeface()==android.graphics.Typeface.MONOSPACE && tv.getText().toString().contains("price =")) stats[3]++;
            CharSequence cs=tv.getText();
            if (cs instanceof Spanned) {
                RichMarkdown.MathSpan[] spans=((Spanned)cs).getSpans(0,cs.length(),RichMarkdown.MathSpan.class);
                if (spans.length>0) {
                    stats[1]+=spans.length;
                    if (cs.toString().startsWith("$$")) {
                        stats[0]++;
                        if (cs.toString().contains("x^5+1=")) stats[4]++;
                    }
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for (int i=0;i<g.getChildCount();i++) inspectMath(g.getChildAt(i),stats);
        }
    }

    private static boolean hasCurrencyOutsideMath(View v,String needle) {
        if (!v.isShown()) return false;
        if (v instanceof TextView) {
            CharSequence cs=((TextView)v).getText();
            String s=cs.toString();
            int at=s.indexOf(needle);
            if (at>=0 && (!(cs instanceof Spanned) ||
                    ((Spanned)cs).getSpans(at,at+needle.length(),RichMarkdown.MathSpan.class).length==0)) return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for (int i=0;i<g.getChildCount();i++) if (hasCurrencyOutsideMath(g.getChildAt(i),needle)) return true;
        }
        return false;
    }
}
