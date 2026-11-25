package com.sunwin.deepseekharness;

import android.content.Context;
import android.os.SystemClock;
import android.view.*;
import android.widget.ScrollView;

/** Follow growth after layout. Only an actual scroll away from the tail unpins it. */
final class ConversationScroll extends ScrollView {
    private final int near;
    private boolean follow=true,touch,own,scheduled;
    private long last;
    private View jump;
    ConversationScroll(Context context,int near) { super(context); this.near=near; }
    void jumpButton(View button) { jump=button; indicator(); }
    private int bottom() { return getChildCount()==0?0:Math.max(0,getChildAt(0).getHeight()-getHeight()); }
    private void indicator() {
        if (jump!=null) jump.setVisibility(!follow && bottom()-getScrollY()>near ? VISIBLE : GONE);
    }
    void latest() { follow=true; indicator(); schedule(); }
    void anchor(int y) {
        follow=false; scrollTo(0,y); indicator();
    }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (e.getActionMasked()==MotionEvent.ACTION_DOWN) touch=true;
        boolean result=super.dispatchTouchEvent(e);
        if (e.getActionMasked()==MotionEvent.ACTION_UP || e.getActionMasked()==MotionEvent.ACTION_CANCEL) {
            touch=false; schedule();
        }
        return result;
    }
    @Override protected void onScrollChanged(int x,int y,int oldX,int oldY) {
        super.onScrollChanged(x,y,oldX,oldY);
        if (!own && (touch || y<oldY || !follow)) follow=bottom()-y<=near;
        indicator();
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
        own=true; super.onLayout(changed,l,t,r,b); own=false;
        indicator(); schedule();
    }
    void showNew(View user) {
        if (!follow || touch || user==null) return;
        android.graphics.Rect rect=new android.graphics.Rect();
        user.getDrawingRect(rect); offsetDescendantRectToMyCoords(user,rect);
        // Keep the tapped message in the first viewport while easing the remaining distance.
        int y=Math.min(rect.bottom-getHeight(),rect.top);
        if (getScrollY()<y) { own=true; scrollTo(0,y); own=false; }
    }
    private void schedule() {
        if (!scheduled && follow && !touch && bottom()>getScrollY()) {
            scheduled=true; last=SystemClock.uptimeMillis(); postOnAnimation(step);
        }
    }
    private final Runnable step=new Runnable() {
        public void run() {
            scheduled=false;
            if (!isAttachedToWindow() || !follow || touch) return;
            int distance=bottom()-getScrollY();
            if (distance<=0) return;
            long now=SystemClock.uptimeMillis();
            double fraction=1-Math.exp(-Math.max(1,now-last)/62.0);
            own=true; scrollBy(0,Math.max(1,(int)Math.ceil(distance*fraction))); own=false;
            schedule();
        }
    };
}
