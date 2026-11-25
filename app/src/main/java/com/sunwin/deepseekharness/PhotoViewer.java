package com.sunwin.deepseekharness;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import java.util.*;

public final class PhotoViewer extends Activity {
    private ArrayList<String> paths;
    private int index;
    private Picture picture;
    private TextView counter;

    static void open(Activity activity,List<String> paths,int index) {
        activity.startActivity(new Intent(activity,PhotoViewer.class)
                .putStringArrayListExtra("photos",new ArrayList<>(paths)).putExtra("index",index));
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        paths=getIntent().getStringArrayListExtra("photos");
        index=state==null ? getIntent().getIntExtra("index",0) : state.getInt("index");
        if (paths==null || index<0 || index>=paths.size()) { finish(); return; }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        FrameLayout root=new FrameLayout(this); root.setBackgroundColor(Color.BLACK); setContentView(root);
        picture=new Picture(); root.addView(picture,new FrameLayout.LayoutParams(-1,-1));
        Appearance a=new Appearance(this);
        ImageButton close=a.icon(R.drawable.ic_close,"关闭",this::finish);
        close.setColorFilter(Color.WHITE); close.setBackgroundColor(0x66000000);
        root.addView(close,new FrameLayout.LayoutParams(a.dp(48),a.dp(48),Gravity.TOP|Gravity.START));
        counter=a.text("",15,Color.WHITE,true); counter.setGravity(Gravity.CENTER); counter.setBackgroundColor(0x66000000);
        root.addView(counter,new FrameLayout.LayoutParams(a.dp(66),a.dp(40),Gravity.TOP|Gravity.CENTER_HORIZONTAL));
        show(index);
    }
    @Override protected void onSaveInstanceState(Bundle b) { super.onSaveInstanceState(b); b.putInt("index",index); }
    private void show(int next) {
        if (next<0 || next>=paths.size()) return;
        try {
            Bitmap image=Photos.load(this,paths.get(next),Photos.LONG_EDGE);
            index=next; picture.display(image);
            counter.setText((index+1)+" / "+paths.size()); picture.setContentDescription("照片 "+(index+1));
        } catch (IOException e) { Toast.makeText(this,"图片读取失败",Toast.LENGTH_SHORT).show(); finish(); }
    }
    private final class Picture extends View {
        private Bitmap bitmap;
        private float zoom=1, x, y, downX, downY, lastX, lastY;
        private boolean multi,atStart,atEnd;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private float span,focusX,focusY;
        Picture() { super(PhotoViewer.this); }
        private void pinch(MotionEvent e,int first,int second,boolean move) {
            float ax=e.getX(first), ay=e.getY(first), bx=e.getX(second), by=e.getY(second);
            float distance=(float)Math.hypot(bx-ax,by-ay), fx=(ax+bx)/2, fy=(ay+by)/2;
            if (move && span>1 && distance>1) {
                float next=Math.max(1,Math.min(6,zoom*distance/span)), ratio=next/zoom;
                x=fx-(focusX-x)*ratio; y=fy-(focusY-y)*ratio;
                zoom=next; constrain(); invalidate();
            }
            span=distance; focusX=fx; focusY=fy;
        }
        void display(Bitmap image) { bitmap=image; center(); }
        private void center() { zoom=1; x=getWidth()/2f; y=getHeight()/2f; invalidate(); }
        private float factor() {
            return zoom*Math.min((float)getWidth()/bitmap.getWidth(),(float)getHeight()/bitmap.getHeight());
        }
        private void constrain() {
            if (bitmap==null) return;
            float w=bitmap.getWidth()*factor()/2, h=bitmap.getHeight()*factor()/2;
            x=2*w<=getWidth() ? getWidth()/2f : Math.max(getWidth()-w,Math.min(w,x));
            y=2*h<=getHeight() ? getHeight()/2f : Math.max(getHeight()-h,Math.min(h,y));
        }
        @Override protected void onSizeChanged(int w,int h,int oldW,int oldH) { center(); }
        @Override protected void onDraw(Canvas canvas) {
            if (bitmap==null) return;
            float w=bitmap.getWidth()*factor()/2, h=bitmap.getHeight()*factor()/2;
            canvas.drawBitmap(bitmap,null,new RectF(x-w,y-h,x+w,y+h),paint);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            float tx=e.getX(), ty=e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    multi=false; downX=tx; downY=ty;
                    float half=bitmap==null ? 0 : bitmap.getWidth()*factor()/2;
                    atStart=x-half>=-1; atEnd=x+half<=getWidth()+1; break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    multi=true; pinch(e,0,1,false); break;
                case MotionEvent.ACTION_POINTER_UP:
                    int up=e.getActionIndex(), remaining=up==0 ? 1 : 0;
                    if (e.getPointerCount()>2) pinch(e,remaining,up<=1 ? 2 : 1,false);
                    lastX=e.getX(remaining); lastY=e.getY(remaining); return true;
                case MotionEvent.ACTION_MOVE:
                    if (e.getPointerCount()>=2) pinch(e,0,1,true);
                    else if (zoom>1) {
                        x+=tx-lastX; y+=ty-lastY; constrain(); invalidate();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    float dx=tx-downX, dy=ty-downY;
                    if (!multi && (dx<0 ? atEnd : atStart) && Math.abs(dx)>48*getResources().getDisplayMetrics().density && Math.abs(dx)>1.2f*Math.abs(dy))
                        show(index+(dx<0 ? 1 : -1));
                    else if (!multi && Math.abs(dx)+Math.abs(dy)<ViewConfiguration.get(PhotoViewer.this).getScaledTouchSlop()) performClick();
                    break;
            }
            lastX=tx; lastY=ty; return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
