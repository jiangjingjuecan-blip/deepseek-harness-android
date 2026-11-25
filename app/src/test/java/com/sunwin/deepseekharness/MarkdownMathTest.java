package com.sunwin.deepseekharness;

import android.app.Activity;
import android.graphics.*;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.view.*;
import android.widget.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import ru.noties.jlatexmath.JLatexMathDrawable;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, application=HarnessApp.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MarkdownMathTest {

    @Test public void currencyIsNeverParsedAsMathWhileAdjacentMathStillWorks() {
        String[] currencyCases={
            "$5 and $10",
            "It costs $5.00 or $10.00 today.",
            "Range: $5-$10 or $5/$10",
            "Price is $5, or $10.",
            "Only $5 left.",
            "Compare $100 and $200.",
            "Escaped \\$x\\$ is literal.",
        };
        for (String sample:currencyCases) {
            MarkdownMath.InlineResult r=MarkdownMath.extractInlineMath(sample);
            assertTrue("Should not parse currency as math: "+sample,r.spans.isEmpty());
        }

        MarkdownMath.InlineResult mixed=MarkdownMath.extractInlineMath("Costs $5 and $10, solve $x^2+1=0$ for $x$.");
        assertEquals(2,mixed.spans.size());
        assertEquals("x^2+1=0",mixed.spans.get(0).latex);
        assertEquals("$x^2+1=0$",mixed.spans.get(0).raw);
        assertEquals("x",mixed.spans.get(1).latex);
        assertTrue(mixed.text.contains("$5 and $10"));

        MarkdownMath.InlineResult mixed2=MarkdownMath.extractInlineMath("$5 or $x$");
        assertEquals(1,mixed2.spans.size());
        assertEquals("x",mixed2.spans.get(0).latex);

        MarkdownMath.InlineResult parens=MarkdownMath.extractInlineMath("Inline \\(\\frac{2}{5}(1-x\\cos\\theta)\\) works.");
        assertEquals(1,parens.spans.size());
        assertEquals("\\frac{2}{5}(1-x\\cos\\theta)",parens.spans.get(0).latex);
        assertEquals("\\(\\frac{2}{5}(1-x\\cos\\theta)\\)",parens.spans.get(0).raw);
    }

    @Test public void codeSpansAndCodeBlocksShieldMathDelimiters() {
        MarkdownMath.InlineResult inline=MarkdownMath.extractInlineMath("Use `$x+y$` and `\\(a+b\\)` in `$5`, then $z^2$.");
        assertEquals(1,inline.spans.size());
        assertEquals("z^2",inline.spans.get(0).latex);
        assertTrue(inline.text.contains("`$x+y$`"));

        String fenced="Intro\n```python\nprice = \"$5 and $10\"\neq = \"$$x^2$$\"\n```\n$$a^2+b^2=c^2$$";
        List<MarkdownMath.Block> blocks=MarkdownMath.parseBlocks(fenced);
        assertEquals(3,blocks.size());
        assertEquals(MarkdownMath.BlockType.PROSE,blocks.get(0).type);
        assertEquals(MarkdownMath.BlockType.CODE_BLOCK,blocks.get(1).type);
        assertEquals("python",blocks.get(1).info);
        assertTrue(blocks.get(1).content.contains("$$x^2$$"));
        assertEquals(MarkdownMath.BlockType.DISPLAY_MATH,blocks.get(2).type);
        assertEquals("a^2+b^2=c^2",blocks.get(2).content);
    }

    @Test public void displayMathHandlesAllDelimitersAndUnclosedStreamingGracefully() {
        String unclosed="### 结果\n\n$$x^5+1=(x+1)\\left(x^4-x^3";
        List<MarkdownMath.Block> partial=MarkdownMath.parseBlocks(unclosed);
        assertEquals(1,partial.size());
        assertEquals(MarkdownMath.BlockType.PROSE,partial.get(0).type);
        assertTrue(partial.get(0).content.contains("$$x^5+1"));

        String closed=unclosed+"+x^2-x+1\\right)$$\n\n---";
        List<MarkdownMath.Block> done=MarkdownMath.parseBlocks(closed);
        assertEquals(3,done.size());
        assertEquals(MarkdownMath.BlockType.PROSE,done.get(0).type);
        assertEquals("### 结果",done.get(0).content);
        assertEquals(MarkdownMath.BlockType.DISPLAY_MATH,done.get(1).type);
        assertEquals("x^5+1=(x+1)\\left(x^4-x^3+x^2-x+1\\right)",done.get(1).content);
        assertEquals(MarkdownMath.BlockType.PROSE,done.get(2).type);
        assertEquals("---",done.get(2).content);

        String brackets="Before\n\\[\n\\int_0^1 \\frac{dx}{x^5+1}\n\\]\nAfter";
        List<MarkdownMath.Block> b=MarkdownMath.parseBlocks(brackets);
        assertEquals(3,b.size());
        assertEquals(MarkdownMath.BlockType.DISPLAY_MATH,b.get(1).type);
        assertEquals("\\int_0^1 \\frac{dx}{x^5+1}",b.get(1).content);

        // Unclosed \[ stays prose while streaming
        List<MarkdownMath.Block> unclosedBracket=MarkdownMath.parseBlocks("Text \\[ \\frac{1}{2");
        assertEquals(1,unclosedBracket.size());
        assertEquals(MarkdownMath.BlockType.PROSE,unclosedBracket.get(0).type);
    }

    @Test public void tablesAndRichSyntaxDetection() {
        assertFalse(MarkdownMath.hasRichSyntax("你好，这是一段普通中文回复。"));
        assertFalse(MarkdownMath.hasRichSyntax("Plain English reply 123."));
        assertTrue(MarkdownMath.hasRichSyntax("### 结果"));
        assertTrue(MarkdownMath.hasRichSyntax("$$x^2$$"));
        assertTrue(MarkdownMath.hasRichSyntax("inline $x$"));
        assertTrue(MarkdownMath.hasRichSyntax("inline \\(x\\)"));
        assertTrue(MarkdownMath.hasRichSyntax("- item"));
        assertTrue(MarkdownMath.hasRichSyntax("1. item"));
        assertTrue(MarkdownMath.hasRichSyntax("[link](https://example.com)"));

        String md="总结：\n| 项 | 公式 | 值 |\n| :--- | :---: | ---: |\n| A | $\\frac{1}{5}$ | 0.2 |\n| B | $x^2$ | 4 |\n结束";
        List<MarkdownMath.Block> blocks=MarkdownMath.parseBlocks(md);
        assertEquals(3,blocks.size());
        assertEquals(MarkdownMath.BlockType.TABLE,blocks.get(1).type);
        MarkdownMath.TableData table=MarkdownMath.parseTable(blocks.get(1).content);
        assertNotNull(table);
        assertEquals(Arrays.asList("项","公式","值"),table.headers);
        assertEquals(Arrays.asList(-1,0,1),table.alignments);
        assertEquals(2,table.rows.size());
        assertEquals("$\\frac{1}{5}$",table.rows.get(0).get(1));
    }

    @Test public void jLatexMathCompilesAndDrawsAllRequiredFormulasAndPreservesCopySource() {
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).create();
        MainActivity activity=controller.get();
        RichMarkdown rich=new RichMarkdown(activity,new Appearance(activity),null);
        String[] formulas={
            "x^5+1=(x+1)\\left(x^4-x^3+x^2-x+1\\right)",
            "\\frac{1}{5(x+1)}",
            "\\frac{2}{5}(1-x\\cos\\theta)",
            "\\Bigg|_{\\theta=\\pi/5,\\;3\\pi/5}",
            "\\int_0^1 \\frac{dx}{x^5+1}",
            "\\sum_{k=1}^{n} \\frac{1}{k^2} = \\frac{\\pi^2}{6}",
            "\\frac{1}{5}\\ln(x+1) - \\frac{2}{5}\\cos\\theta \\cdot \\ln(x^2-2x\\cos\\theta+1) + \\frac{2}{5}\\sin\\theta\\arctan\\left(\\frac{x-\\cos\\theta}{\\sin\\theta}\\right)",
            "\\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix}",
            "\\begin{aligned} a &= b + c \\\\ d &= e \\end{aligned}",
            "\\begin{align*} f(x) &= x^2 \\\\ g(x) &= x^3 \\end{align*}",
            "\\begin{cases} x^2 & x \\ge 0 \\\\ -x & x < 0 \\end{cases}",
            "\\dfrac{1}{2} + \\tfrac{3}{4} + \\binom{n}{k} + \\left.\\frac{x^2}{2}\\right|_0^1",
            "x \\in \\mathbb{R},\\; \\mathcal{O}(n\\log n),\\; \\text{where } x > 0",
            "\\text{当 } x > 0 \\text{ 时}",
        };
        for (String formula:formulas) {
            JLatexMathDrawable d=rich.compileMath(formula,44f,Appearance.INK,true);
            assertNotNull("JLatexMath must compile: "+formula,d);
            assertTrue("width>0 for "+formula,d.getIntrinsicWidth()>0);
            assertTrue("height>0 for "+formula,d.getIntrinsicHeight()>0);
            Bitmap bmp=Bitmap.createBitmap(Math.min(600,d.getIntrinsicWidth()+8),Math.min(300,d.getIntrinsicHeight()+8),Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(bmp);
            d.setBounds(0,0,d.getIntrinsicWidth(),d.getIntrinsicHeight());
            d.draw(canvas);
            boolean hasPixel=false;
            for (int x=0;x<bmp.getWidth() && !hasPixel;x+=2)
                for (int y=0;y<bmp.getHeight() && !hasPixel;y+=2)
                    if (bmp.getPixel(x,y)!=0) hasPixel=true;
            assertTrue("Formula should draw visible pixels: "+formula,hasPixel);
            bmp.recycle();
        }

        // Verify inline and display math keep exact original LaTeX source in CharSequence for copy.
        String prose="系数为 $\\frac{2}{5}(1-x\\cos\\theta)$，价格 $5 and $10。";
        SpannableStringBuilder rendered=rich.renderMarkdown(prose,40f,Appearance.INK);
        assertTrue("Copy source preserves raw inline math",rendered.toString().contains("$\\frac{2}{5}(1-x\\cos\\theta)$"));
        assertTrue("Currency stays intact",rendered.toString().contains("$5 and $10"));
        RichMarkdown.MathSpan[] spans=rendered.getSpans(0,rendered.length(),RichMarkdown.MathSpan.class);
        assertEquals(1,spans.length);

        String displayRaw="$$x^5+1=(x+1)\\left(x^4-x^3+x^2-x+1\\right)$$";
        CharSequence displayRendered=rich.renderDisplayMath(displayRaw,"x^5+1=(x+1)\\left(x^4-x^3+x^2-x+1\\right)",40f,Appearance.INK);
        assertEquals(displayRaw,displayRendered.toString());

        // Verify dark-theme light paint color draws cleanly through MathSpan.
        Bitmap darkBmp=Bitmap.createBitmap(200,80,Bitmap.Config.ARGB_8888);
        Canvas darkCanvas=new Canvas(darkBmp);
        Paint darkPaint=new Paint(); darkPaint.setColor(Color.WHITE);
        spans[0].draw(darkCanvas,rendered,0,rendered.length(),4f,0,40,80,darkPaint);
        darkBmp.recycle();

        // Verify wide display math starts at x=0 (never left-clipped) and scrolls horizontally, while narrow math centers.
        LinearLayout host=new LinearLayout(activity); host.setOrientation(LinearLayout.VERTICAL);
        String wide="$$\\int_0^1 \\frac{dx}{x^5+1} = \\frac{1}{5}\\ln 2 + \\sum_{\\theta}\\left[\\frac{2}{5}\\sin\\theta\\arctan\\left(\\frac{x-\\cos\\theta}{\\sin\\theta}\\right)\\right]\\Bigg|_{\\theta=\\pi/5,\\;3\\pi/5}$$";
        rich.bindBlocks(host,MarkdownMath.parseBlocks(wide+"\n\n$$x^2+1=0$$"),40f,Appearance.INK,null,null);
        int vw=View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY);
        int vh=View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.AT_MOST);
        host.measure(vw,vh); host.layout(0,0,360,host.getMeasuredHeight());
        HorizontalScrollView wideScroll=(HorizontalScrollView)host.getChildAt(0);
        View wideInner=wideScroll.getChildAt(0);
        assertEquals("Wide display math must start at x=0 (no left clip)",0,wideInner.getLeft());
        assertTrue("Wide display math must exceed viewport width to scroll",wideInner.getWidth()>wideScroll.getWidth());
        HorizontalScrollView narrowScroll=(HorizontalScrollView)host.getChildAt(1);
        LinearLayout narrowInner=(LinearLayout)narrowScroll.getChildAt(0);
        assertEquals("Narrow display math fills viewport for centering",narrowScroll.getWidth(),narrowInner.getWidth());
        assertTrue("Narrow display math text centered",narrowInner.getChildAt(0).getLeft()>0);

        controller.destroy();
    }

    @Test public void streamingBubbleTransitionsFromIncompleteDisplayMathToRenderedBlocks() {
        HarnessApp app=(HarnessApp)RuntimeEnvironment.getApplication();
        History h=app.history; long chat=h.create();
        long row=h.begin(chat,"展开公式",Collections.emptyList());
        app.getSharedPreferences("ui",0).edit().putLong("chat",chat).putBoolean("batteryAsked",true)
                .putBoolean("notificationAsked",true).putInt(Typewriter.PREF,0).commit();
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();
        ChatEngine.Work w=new ChatEngine.Work(h.chat(chat),"deepseek-flash");
        w.row=w.localReply=row; w.user=h.last(w.chat.branch,row);
        w.webStatus="搜索 部分分式展开";
        app.chat.latest=w;
        app.changed(); Shadows.shadowOf(Looper.getMainLooper()).idle();

        View root=controller.get().getWindow().getDecorView();
        assertNotNull("Web status chip shown while searching",findText(root,"搜索 部分分式展开"));

        // Incomplete display math during streaming should not throw and should collapse status chip.
        w.webStatus="";
        w.body="### 结果\n\n$$x^5+1=(x+1)";
        app.streamChanged();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(150,TimeUnit.MILLISECONDS);
        assertNull("Web status chip collapses once text arrives",findShownText(root,"搜索 部分分式展开"));

        // Complete display math + inline math + citation chip.
        String full="### 结果\n\n$$x^5+1=(x+1)\\left(x^4-x^3+x^2-x+1\\right)$$\n\n其中 $\\frac{2}{5}(1-x\\cos\\theta)$。";
        w.body=full;
        w.sources=Collections.singletonList(new WebTools.Source("https://example.com/math","部分分式","MathWiki"));
        w.ended=true;
        h.update(row,full,"done",null,null,WebTools.serializeSources(w.sources));
        app.streamChanged();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(150,TimeUnit.MILLISECONDS);
        assertNotNull("Source chip displayed",findShownText(root,"MathWiki"));
        assertTrue("Display math HorizontalScrollView rendered",hasHorizontalScroll(root));

        controller.pause().stop().destroy();
    }

    private static TextView findText(View v,String target) {
        if (v instanceof TextView && target.contentEquals(((TextView)v).getText())) return (TextView)v;
        if (v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for (int i=0;i<g.getChildCount();i++) { TextView hit=findText(g.getChildAt(i),target); if (hit!=null) return hit; }
        }
        return null;
    }
    private static TextView findShownText(View v,String target) {
        if (!v.isShown()) return null;
        if (v instanceof TextView && target.contentEquals(((TextView)v).getText())) return (TextView)v;
        if (v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for (int i=0;i<g.getChildCount();i++) { TextView hit=findShownText(g.getChildAt(i),target); if (hit!=null) return hit; }
        }
        return null;
    }
    private static boolean hasHorizontalScroll(View v) {
        if (v instanceof HorizontalScrollView && v.isShown()) {
            HorizontalScrollView hsv=(HorizontalScrollView)v;
            if (hsv.getChildCount()>0 && hsv.getChildAt(0) instanceof LinearLayout) {
                LinearLayout ll=(LinearLayout)hsv.getChildAt(0);
                if (ll.getChildCount()>0 && ll.getChildAt(0) instanceof TextView) {
                    CharSequence cs=((TextView)ll.getChildAt(0)).getText();
                    if (cs instanceof android.text.Spanned &&
                            ((android.text.Spanned)cs).getSpans(0,cs.length(),RichMarkdown.MathSpan.class).length>0) return true;
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for (int i=0;i<g.getChildCount();i++) if (hasHorizontalScroll(g.getChildAt(i))) return true;
        }
        return false;
    }
}
