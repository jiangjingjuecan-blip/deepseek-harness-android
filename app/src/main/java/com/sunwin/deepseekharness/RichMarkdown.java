package com.sunwin.deepseekharness;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.text.*;
import android.text.method.ArrowKeyMovementMethod;
import android.text.style.*;
import android.util.LruCache;
import android.view.*;
import android.widget.*;
import io.noties.markwon.*;
import io.noties.markwon.core.MarkwonTheme;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import ru.noties.jlatexmath.*;
import java.util.*;

/**
 * Offline Markdown + LaTeX math renderer using Markwon and JLatexMath.
 * Inline and display math keep their original LaTeX text as the underlying CharSequence
 * covered by a ReplacementSpan so text selection/copy yields the original source.
 */
final class RichMarkdown {
    private static volatile boolean latexInit;
    private final Context context;
    private final Appearance a;
    private final Markwon markwon;
    private final LruCache<String,JLatexMathDrawable> mathCache=new LruCache<>(96);

    RichMarkdown(Context context,Appearance a,java.util.function.Consumer<Uri> openLink) {
        this.context=context; this.a=a;
        ensureLatexInit(context);
        AbstractMarkwonPlugin theme=new AbstractMarkwonPlugin() {
            @Override public void configureTheme(MarkwonTheme.Builder builder) {
                builder.linkColor(Appearance.BLUE)
                       .headingBreakHeight(0)
                       .thematicBreakColor(Appearance.LINE)
                       .thematicBreakHeight(a.dp(1))
                       .blockQuoteColor(Appearance.BLUE)
                       .blockQuoteWidth(a.dp(3))
                       .codeBackgroundColor(0xfff1f3f8)
                       .codeBlockBackgroundColor(0xfff1f3f8)
                       .codeTextColor(Appearance.INK)
                       .codeTypeface(Typeface.MONOSPACE);
            }
            @Override public void configureConfiguration(MarkwonConfiguration.Builder builder) {
                builder.linkResolver((view,link)->{
                    String safe=WebTools.safeHttpUrl(link);
                    if (safe!=null && openLink!=null) openLink.accept(Uri.parse(safe));
                });
            }
        };
        this.markwon=Markwon.builder(context)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(theme)
                .build();
    }

    static void ensureLatexInit(Context ctx) {
        if (latexInit) return;
        synchronized (RichMarkdown.class) {
            if (!latexInit) {
                JLatexMathAndroid.init(ctx.getApplicationContext());
                latexInit=true;
            }
        }
    }

    JLatexMathDrawable compileMath(String latex,float sizePx,int color,boolean display) {
        String normalized=MarkdownMath.normalizeLatex(latex);
        if (normalized.isEmpty()) return null;
        int rounded=Math.round(sizePx*2f);
        String key=(display?"D:":"I:")+rounded+":"+color+":"+normalized;
        JLatexMathDrawable cached=mathCache.get(key);
        if (cached!=null) return cached;
        try {
            JLatexMathDrawable drawable=JLatexMathDrawable.builder(normalized)
                    .textSize(sizePx)
                    .color(color)
                    .align(display ? JLatexMathDrawable.ALIGN_CENTER : JLatexMathDrawable.ALIGN_LEFT)
                    .padding(display ? a.dp(4) : 0)
                    .fitCanvas(false)
                    .build();
            if (drawable.getIntrinsicWidth()<=0 || drawable.getIntrinsicHeight()<=0) return null;
            mathCache.put(key,drawable);
            return drawable;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Render markdown prose with inline LaTeX spans preserved over original source text. */
    SpannableStringBuilder renderMarkdown(String source,float textSizePx,int textColor) {
        MarkdownMath.InlineResult inline=MarkdownMath.extractInlineMath(source);
        Spanned parsed=markwon.toMarkdown(inline.text);
        SpannableStringBuilder sb=new SpannableStringBuilder(parsed);
        if (!inline.spans.isEmpty()) replacePlaceholders(sb,inline.spans,textSizePx,textColor);
        return sb;
    }

    /** Render display math into a selectable Spannable whose copy value is the original LaTeX source. */
    CharSequence renderDisplayMath(String raw,String latex,float textSizePx,int textColor) {
        JLatexMathDrawable drawable=compileMath(latex,textSizePx*1.16f,textColor,true);
        if (drawable==null) return raw;
        SpannableStringBuilder sb=new SpannableStringBuilder(raw);
        sb.setSpan(new MathSpan(drawable,textColor,a.dp(2),a.dp(4)),0,sb.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    void bindProse(TextView tv,String source,float textSizePx,int textColor) {
        SpannableStringBuilder spanned=renderMarkdown(source,textSizePx,textColor);
        markwon.setParsedMarkdown(tv,spanned);
        tv.setMovementMethod(SelectableLinkMovementMethod.INSTANCE);
    }

    private void replacePlaceholders(SpannableStringBuilder sb,List<MarkdownMath.InlineSpan> spans,
                                     float textSizePx,int textColor) {
        for (int i=spans.size()-1;i>=0;i--) {
            String token=MarkdownMath.placeholder(i);
            int at=TextUtils.indexOf(sb,token);
            while (at>=0) {
                MarkdownMath.InlineSpan span=spans.get(i);
                sb.replace(at,at+token.length(),span.raw);
                JLatexMathDrawable drawable=compileMath(span.latex,textSizePx*1.04f,textColor,false);
                if (drawable!=null) {
                    sb.setSpan(new MathSpan(drawable,textColor,a.dp(1),a.dp(1)),at,at+span.raw.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                at=TextUtils.indexOf(sb,token,at+span.raw.length());
            }
        }
    }

    /** Render a complete block sequence into a container, recycling unchanged views during streaming. */
    void bindBlocks(LinearLayout container,List<MarkdownMath.Block> blocks,float textSizePx,int textColor,
                    View.OnClickListener onTap,View.OnLongClickListener onHold) {
        int count=blocks.size();
        while (container.getChildCount()>count) container.removeViewAt(container.getChildCount()-1);
        for (int i=0;i<count;i++) {
            MarkdownMath.Block block=blocks.get(i);
            String sig=block.type.name()+"|"+block.info+"|"+textColor+"|"+block.raw;
            View existing=i<container.getChildCount() ? container.getChildAt(i) : null;
            if (existing!=null && sig.equals(existing.getTag())) continue;
            View view=createBlockView(block,textSizePx,textColor,onTap,onHold);
            view.setTag(sig);
            LinearLayout.LayoutParams lp=a.size(-1,-2);
            if (i>0) lp.topMargin=a.dp(block.type==MarkdownMath.BlockType.DISPLAY_MATH ? 6 : 8);
            if (existing!=null) {
                container.removeViewAt(i);
                container.addView(view,i,lp);
            } else {
                container.addView(view,lp);
            }
        }
    }

    private View createBlockView(MarkdownMath.Block block,float textSizePx,int textColor,
                                 View.OnClickListener onTap,View.OnLongClickListener onHold) {
        switch (block.type) {
            case DISPLAY_MATH: {
                HorizontalScrollView hsv=new HorizontalScrollView(context);
                hsv.setHorizontalScrollBarEnabled(true);
                hsv.setFillViewport(true);
                hsv.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
                LinearLayout center=a.column();
                center.setGravity(Gravity.CENTER_HORIZONTAL);
                a.pad(center,4,6);
                TextView tv=a.text("",16,textColor,false);
                tv.setTextIsSelectable(true);
                tv.setMaxLines(1);
                tv.setHorizontallyScrolling(true);
                tv.setText(renderDisplayMath(block.raw,block.content,textSizePx,textColor));
                tv.setOnClickListener(onTap);
                tv.setOnLongClickListener(onHold);
                center.setOnClickListener(onTap);
                center.setOnLongClickListener(onHold);
                center.addView(tv,a.size(-2,-2));
                // Use START gravity on the HorizontalScrollView child so wide equations start at x=0
                // and scroll cleanly to the right instead of clipping off the left edge; fillViewport
                // + center.setGravity(CENTER_HORIZONTAL) centers equations narrower than the bubble.
                hsv.addView(center,new FrameLayout.LayoutParams(-2,-2));
                return hsv;
            }
            case CODE_BLOCK: {
                LinearLayout card=a.column();
                card.setBackground(a.shape(0xfff1f3f8,12,Appearance.LINE));
                a.pad(card,12,9);
                if (!block.info.isEmpty()) {
                    TextView lang=a.text(block.info,11,Appearance.DIM,true);
                    LinearLayout.LayoutParams ll=a.size(-2,-2);
                    ll.bottomMargin=a.dp(4);
                    card.addView(lang,ll);
                }
                HorizontalScrollView hsv=new HorizontalScrollView(context);
                hsv.setHorizontalScrollBarEnabled(true);
                hsv.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
                TextView code=a.text(block.content,13,textColor,false);
                code.setTypeface(Typeface.MONOSPACE);
                code.setTextIsSelectable(true);
                code.setHorizontallyScrolling(true);
                code.setOnClickListener(onTap);
                code.setOnLongClickListener(onHold);
                hsv.addView(code,new FrameLayout.LayoutParams(-2,-2));
                card.addView(hsv,a.size(-1,-2));
                card.setOnClickListener(onTap);
                card.setOnLongClickListener(onHold);
                return card;
            }
            case TABLE: {
                MarkdownMath.TableData table=MarkdownMath.parseTable(block.content);
                if (table==null) {
                    TextView fallback=a.text("",16,textColor,false);
                    fallback.setTextIsSelectable(true);
                    bindProse(fallback,block.content,textSizePx,textColor);
                    fallback.setOnClickListener(onTap);
                    fallback.setOnLongClickListener(onHold);
                    return fallback;
                }
                HorizontalScrollView hsv=new HorizontalScrollView(context);
                hsv.setHorizontalScrollBarEnabled(true);
                hsv.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
                TableLayout grid=new TableLayout(context);
                grid.setBackground(a.shape(Appearance.WHITE,8,Appearance.LINE));
                int border=a.dp(1);
                grid.setPadding(border,border,border,border);
                grid.addView(tableRow(table.headers,table.alignments,true,0xfff1f3f8,textSizePx*0.92f,textColor,onTap,onHold));
                for (int r=0;r<table.rows.size();r++) {
                    View divider=new View(context);
                    divider.setBackgroundColor(Appearance.LINE);
                    grid.addView(divider,new TableLayout.LayoutParams(-1,border));
                    int bg=(r & 1)==0 ? Appearance.WHITE : 0xfffafaf8;
                    grid.addView(tableRow(table.rows.get(r),table.alignments,false,bg,textSizePx*0.92f,textColor,onTap,onHold));
                }
                hsv.addView(grid,new FrameLayout.LayoutParams(-2,-2));
                return hsv;
            }
            default: {
                TextView tv=a.text("",16,textColor,false);
                tv.setTextIsSelectable(true);
                bindProse(tv,block.content,textSizePx,textColor);
                tv.setOnClickListener(onTap);
                tv.setOnLongClickListener(onHold);
                return tv;
            }
        }
    }

    private TableRow tableRow(List<String> cells,List<Integer> alignments,boolean header,int bg,
                              float textSizePx,int textColor,
                              View.OnClickListener onTap,View.OnLongClickListener onHold) {
        TableRow row=new TableRow(context);
        row.setBackgroundColor(bg);
        for (int c=0;c<cells.size();c++) {
            TextView cell=a.text("",14,textColor,header);
            a.pad(cell,10,7);
            cell.setTextIsSelectable(true);
            int align=c<alignments.size() ? alignments.get(c) : -1;
            cell.setGravity(Gravity.CENTER_VERTICAL | (align==0 ? Gravity.CENTER_HORIZONTAL : align>0 ? Gravity.END : Gravity.START));
            bindProse(cell,cells.get(c),textSizePx,textColor);
            cell.setOnClickListener(onTap);
            cell.setOnLongClickListener(onHold);
            row.addView(cell);
        }
        return row;
    }

    /** Preserves native text selection & copy while dispatching single taps on Markdown links. */
    private static final class SelectableLinkMovementMethod extends ArrowKeyMovementMethod {
        static final SelectableLinkMovementMethod INSTANCE=new SelectableLinkMovementMethod();
        @Override public boolean onTouchEvent(TextView widget,Spannable buffer,MotionEvent event) {
            if (event.getAction()==MotionEvent.ACTION_UP) {
                int x=(int)event.getX()-widget.getTotalPaddingLeft()+widget.getScrollX();
                int y=(int)event.getY()-widget.getTotalPaddingTop()+widget.getScrollY();
                Layout layout=widget.getLayout();
                if (layout!=null && y>=0 && y<=layout.getHeight()) {
                    int line=layout.getLineForVertical(y);
                    int off=layout.getOffsetForHorizontal(line,x);
                    ClickableSpan[] links=buffer.getSpans(off,off,ClickableSpan.class);
                    if (links.length>0 && !widget.hasSelection()) {
                        links[0].onClick(widget);
                        return true;
                    }
                }
            }
            return super.onTouchEvent(widget,buffer,event);
        }
    }

    /** Draws a JLatexMathDrawable inline or in display mode with exact baseline alignment and theme-color sync. */
    static final class MathSpan extends ReplacementSpan {
        final JLatexMathDrawable drawable;
        private final int baseColor, hPad, vPad;

        MathSpan(JLatexMathDrawable drawable,int baseColor,int hPad,int vPad) {
            this.drawable=drawable; this.baseColor=baseColor; this.hPad=hPad; this.vPad=vPad;
        }

        @Override public int getSize(Paint paint,CharSequence text,int start,int end,Paint.FontMetricsInt fm) {
            int w=Math.max(1,drawable.getIntrinsicWidth());
            int h=Math.max(1,drawable.getIntrinsicHeight());
            int depth=0;
            try { depth=Math.max(0,Math.min(h,drawable.icon().getIconDepth())); }
            catch (Throwable ignored) { }
            int ascent=h-depth;
            if (fm!=null) {
                fm.ascent=Math.min(fm.ascent,-ascent-vPad);
                fm.top=Math.min(fm.top,fm.ascent);
                fm.descent=Math.max(fm.descent,depth+vPad);
                fm.bottom=Math.max(fm.bottom,fm.descent);
            }
            return w+hPad*2;
        }

        @Override public void draw(Canvas canvas,CharSequence text,int start,int end,
                                   float x,int top,int y,int bottom,Paint paint) {
            int w=Math.max(1,drawable.getIntrinsicWidth());
            int h=Math.max(1,drawable.getIntrinsicHeight());
            int depth=0;
            try { depth=Math.max(0,Math.min(h,drawable.icon().getIconDepth())); }
            catch (Throwable ignored) { }
            int ascent=h-depth;
            int color=paint.getColor();
            if (color!=baseColor) drawable.setColorFilter(new PorterDuffColorFilter(color,PorterDuff.Mode.SRC_IN));
            else drawable.setColorFilter(null);
            drawable.setBounds(0,0,w,h);
            canvas.save();
            canvas.translate(x+hPad,y-ascent);
            drawable.draw(canvas);
            canvas.restore();
        }
    }
}
