package com.sunwin.deepseekharness;

import android.app.Instrumentation;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.Locale;
import java.util.concurrent.*;

/** Same ACTION_UP, visible text draw and vsync sampling on both release APKs. */
final class DeviceSendBench {
    private final Instrumentation test;
    private final MainActivity activity;
    private final View root;
    private final HarnessApp app;
    String stage="bench-ime";
    DeviceSendBench(Instrumentation test,MainActivity activity) {
        this.test=test; this.activity=activity; root=activity.getWindow().getDecorView();
        app=(HarnessApp)activity.getApplication();
    }
    static View find(View v,String value,boolean prefix) {
        if (value==null && v instanceof EditText) return v;
        if (value!=null && !(v instanceof EditText)) {
            if (value.contentEquals(v.getContentDescription()==null?"":v.getContentDescription())) return v;
            if (v instanceof TextView) {
                CharSequence text=((TextView)v).getText();
                if (prefix ? text.toString().startsWith(value) : value.contentEquals(text)) return v;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)v;
            for (int i=0;i<group.getChildCount();i++) {
                View found=find(group.getChildAt(i),value,prefix);
                if (found!=null) return found;
            }
        }
        return null;
    }
    static ScrollView threadScroll(View view) {
        if (view instanceof ScrollView) return (ScrollView)view;
        if (view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) {
                ScrollView found=threadScroll(group.getChildAt(i));
                if (found!=null) return found;
            }
        }
        return null;
    }
    void run(Bundle result) throws Exception {
        activity.getSharedPreferences("ui",0).edit().remove("reveal_speed").commit();
        EditText input=(EditText)find(root,null,false);
        test.runOnMainSync(()->{
            input.requestFocus();
            activity.getSystemService(InputMethodManager.class).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);
        });
        SystemClock.sleep(1200);
        for (int i=1;i<=5;i++) {
            String text="采样"+i;
            Sample sample=new Sample(input,text,"溪水"+i);
            stage="bench-tap-"+i;
            test.runOnMainSync(()->input.setText(text));
            test.waitForIdleSync();
            SystemClock.sleep(300);
            test.runOnMainSync(sample::start);
            android.util.Log.i("SendBench","tap "+i);
            try {
                stage="bench-draw-"+i;
                if (!sample.finished.await(60,TimeUnit.SECONDS)) throw new AssertionError();
                result.putString("sample-"+i,sample.report());
                android.util.Log.i("SendBench","sample "+i+" "+sample.report());
            } finally { test.runOnMainSync(sample::detach); }
            long until=SystemClock.elapsedRealtime()+20000;
            while (app.chat.busy() && SystemClock.elapsedRealtime()<until) SystemClock.sleep(50);
            if (app.chat.busy()) throw new AssertionError();
            SystemClock.sleep(900);
        }
    }
    private final class Sample implements Choreographer.FrameCallback,ViewTreeObserver.OnDrawListener,ViewTreeObserver.OnScrollChangedListener {
        final ScrollView scroll=threadScroll(root);
        final EditText input;
        final String question,prefix;
        final CountDownLatch finished=new CountDownLatch(1);
        final Rect visible=new Rect();
        final int[] xy=new int[2];
        final int[] intervals={0,0}, slow={0,0}, missed={0,0};
        long tap,draw,reply,last;
        int height,inputY,range,maxShift,scrollY,largestStep,scrollMoves;
        boolean focused=true,armed;
        Sample(EditText input,String question,String prefix) { this.input=input; this.question=question; this.prefix=prefix; }
        void start() {
            View button=find(root,"发送",false);
            if (button==null || !button.isEnabled()) return;
            root.getWindowVisibleDisplayFrame(visible); height=visible.bottom;
            input.getLocationOnScreen(xy); inputY=xy[1];
            root.getViewTreeObserver().addOnDrawListener(this);
            scrollY=scroll.getScrollY(); root.getViewTreeObserver().addOnScrollChangedListener(this);
            Choreographer.getInstance().postFrameCallback(this);
            button.getLocationInWindow(xy);
            float x=xy[0]+button.getWidth()/2f,y=xy[1]+button.getHeight()/2f;
            long down=SystemClock.uptimeMillis();
            touch(MotionEvent.ACTION_DOWN,down,x,y);
            armed=true; tap=System.nanoTime();
            touch(MotionEvent.ACTION_UP,down,x,y);
        }
        void touch(int action,long down,float x,float y) {
            MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);
            try { activity.dispatchTouchEvent(e); } finally { e.recycle(); }
        }
        @Override public void onDraw() {
            if (!armed) return;
            long now=System.nanoTime();
            if (now-tap<500_000_000L) {
                root.getWindowVisibleDisplayFrame(visible);
                range=Math.max(range,Math.abs(visible.bottom-height));
                input.getLocationOnScreen(xy); maxShift=Math.max(maxShift,Math.abs(xy[1]-inputY));
                focused &= input.hasFocus();
            }
            if (draw==0) {
                View bubble=find(scroll,question,false);
                if (bubble!=null && bubble.getGlobalVisibleRect(visible)) draw=now;
            }
            if (reply==0) {
                View bubble=find(scroll,prefix,true);
                if (bubble!=null && bubble.getGlobalVisibleRect(visible)) reply=now;
            }
        }
        @Override public void onScrollChanged() {
            int y=scroll.getScrollY();
            // Include the layout which first exposes the sent bubble, even on a slow emulator.
            if (armed && (draw==0 || System.nanoTime()-tap<500_000_000L) && y!=scrollY) {
                largestStep=Math.max(largestStep,Math.abs(y-scrollY)); scrollMoves++;
            }
            scrollY=y;
        }
        @Override public void doFrame(long now) {
            if (!armed) return;
            if (last==0) last=tap;
            int phase=now>=tap && last<tap+500_000_000L ? 0 :
                    reply!=0 && now>=reply && last<reply+2_000_000_000L ? 1 : -1;
            if (phase>=0 && now>last) {
                long slots=Math.max(1,Math.round((now-last)/16_666_667d));
                intervals[phase]++;
                if (slots>1) { slow[phase]++; missed[phase]+=(int)slots-1; }
            }
            last=Math.max(last,now);
            if (draw!=0 && reply!=0 && now>=reply+2_000_000_000L) finished.countDown();
            else Choreographer.getInstance().postFrameCallback(this);
        }
        void detach() {
            armed=false;
            root.getViewTreeObserver().removeOnDrawListener(this);
            root.getViewTreeObserver().removeOnScrollChangedListener(this);
            Choreographer.getInstance().removeFrameCallback(this);
        }
        String report() {
            return String.format(Locale.ROOT,"bubble_ms=%.1f send=%d/%d missed=%d stream=%d/%d missed=%d inset=%d insetDelta=%d inputDelta=%d focus=%s scrollSteps=%d maxScrollPx=%d",
                (draw-tap)/1e6,slow[0],intervals[0],missed[0],slow[1],intervals[1],missed[1],
                root.getHeight()-height,range,maxShift,focused,scrollMoves,largestStep);
        }
    }
}
