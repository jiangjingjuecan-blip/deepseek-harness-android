package com.sunwin.deepseekharness;

import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;

/** Keep the same editor/IME connection as its natural one-to-five-line height changes. */
final class ComposerInput extends EditText {
    private final Height height=new Height();
    ComposerInput(Context context) { super(context); }
    @Override protected void onMeasure(int width,int spec) {
        super.onMeasure(width,spec);
        setMeasuredDimension(getMeasuredWidth(),height.measure(this,getMeasuredHeight()));
    }
    static final class Tray extends HorizontalScrollView {
        private final Height height=new Height();
        Tray(Context context) { super(context); }
        @Override protected void onMeasure(int width,int spec) {
            super.onMeasure(width,spec);
            setMeasuredDimension(getMeasuredWidth(),height.measure(this,getMeasuredHeight()));
        }
    }
    private static final class Height {
        private int target=-1,from,current;
        private long start;
        private boolean scheduled;
        int measure(View v,int natural) {
            long now=SystemClock.uptimeMillis();
            if (target<0) current=natural;
            if (natural!=target) { target=natural; from=current; start=now; }
            if (current!=target) {
                float t=Math.min(1,(now-start)/145f);
                current=target+Math.round((from-target)*(1-t)*(1-t));
                if (current!=target && !scheduled) {
                    scheduled=true; v.postOnAnimation(()->{ scheduled=false; v.requestLayout(); });
                }
            }
            return current;
        }
    }
}
