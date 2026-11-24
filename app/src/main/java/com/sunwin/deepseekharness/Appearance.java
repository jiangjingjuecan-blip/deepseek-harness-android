package com.sunwin.deepseekharness;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

final class Appearance {
    static final int PAPER=0xfffafaf8, INK=0xff202538, DIM=0xff646b7e, BLUE=0xff334fbd, PALE=0xffeaf0ff, LINE=0xffe1e4ec, WHITE=0xffffffff;
    final Activity ui;
    Appearance(Activity ui) { this.ui=ui; }
    int dp(float size) { return Math.round(ui.getResources().getDisplayMetrics().density*size); }
    LinearLayout column() { LinearLayout v=new LinearLayout(ui); v.setOrientation(LinearLayout.VERTICAL); return v; }
    LinearLayout row() { LinearLayout v=new LinearLayout(ui); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    LinearLayout.LayoutParams size(int w,int h) { return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h)); }
    void pad(View v,int x,int y) { v.setPadding(dp(x),dp(y),dp(x),dp(y)); }
    TextView text(String value,int sp,int color,boolean bold) {
        TextView v=new TextView(ui); v.setText(value); v.setTextColor(color); v.setTextSize(sp);
        if (bold) v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        v.setGravity(Gravity.CENTER_VERTICAL); v.setIncludeFontPadding(false); v.setLineSpacing(dp(2),1f);
        return v;
    }
    GradientDrawable shape(int color,int radius,int stroke) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius));
        if (stroke!=0) d.setStroke(dp(1),stroke); return d;
    }
    void buttonStyle(View v,int background,int radius) {
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22334fbd),shape(background,radius,0),shape(WHITE,radius,0)));
        v.setFocusable(true);
    }
    TextView button(String name,boolean filled,Runnable action) {
        TextView v=text(name,15,filled?WHITE:BLUE,true); v.setGravity(Gravity.CENTER); pad(v,18,0);
        buttonStyle(v,filled?BLUE:PALE,15); v.setOnClickListener(w->action.run()); return v;
    }
    ImageButton icon(int resource,String name,Runnable action) {
        ImageButton b=new ImageButton(ui); b.setImageResource(resource); b.setColorFilter(DIM); pad(b,12,12);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER); b.setContentDescription(name); b.setTooltipText(name);
        buttonStyle(b,PAPER,14); b.setOnClickListener(v->action.run()); return b;
    }
    void gap(LinearLayout parent,int height) { parent.addView(new View(ui),size(1,height)); }
}
