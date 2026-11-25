package com.sunwin.deepseekharness;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import com.tom_roush.pdfbox.contentstream.operator.Operator;
import com.tom_roush.pdfbox.cos.*;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.color.*;
import com.tom_roush.pdfbox.pdmodel.graphics.image.*;
import com.tom_roush.pdfbox.text.*;
import com.tom_roush.pdfbox.util.Matrix;
import java.io.*;
import java.util.*;

final class PdfText extends PDFTextStripper {
    private final Context ctx;
    private final Documents.Text out;
    private final List<Placement> placements=new ArrayList<>();
    private final Map<Character,Placement> symbols=new HashMap<>();
    private int positions, inlineBytes;

    private PdfText(Context ctx,Documents.Text out) throws IOException {
        this.ctx=ctx; this.out=out;
        setShouldSeparateByBeads(false);
        setEndPage(Documents.PDF_PAGES);
    }
    private static final class FoundText extends RuntimeException {}
    static boolean read(Context ctx,File file,Documents.Text out) throws IOException {
        MemoryUsageSetting memory=MemoryUsageSetting.setupMixed(4L*1024*1024,64L*1024*1024).setTempDir(Documents.scratch(ctx));
        try (PDDocument pdf=PDDocument.load(file,memory)) {
            if (pdf.isEncrypted() || !pdf.getCurrentAccessPermission().canExtractContent()) throw new IOException("protected");
            // A scan still takes the page-rendering path, even when each page paints an image.
            PDFTextStripper probe=new PDFTextStripper() {
                @Override protected void processTextPosition(TextPosition p) {
                    if (!p.getUnicode().trim().isEmpty()) throw new FoundText();
                }
            };
            probe.setShouldSeparateByBeads(false);
            probe.setEndPage(Documents.PDF_PAGES);
            try { probe.writeText(pdf,new StringWriter()); return false; }
            catch (FoundText found) { /* Start normal layout at page one. */ }
            new PdfText(ctx,out).writeText(pdf,out);
            if (pdf.getNumberOfPages()>Documents.PDF_PAGES) out.more=true;
            return true;
        } catch (NoClassDefFoundError e) { throw new IOException("unsupported-pdf",e); }
    }

    private interface ImageSource { PDImage open() throws IOException; }
    private static final class Placement {
        final RectF bounds;
        final ImageSource image;
        final PDResources resources;
        Placement(Matrix matrix,ImageSource image,PDResources resources) {
            this.image=image; this.resources=resources;
            PointF origin=matrix.transformPoint(0,0);
            bounds=new RectF(origin.x,origin.y,origin.x,origin.y);
            for (int i=1;i<4;i++) {
                PointF point=matrix.transformPoint(i&1,i>>1);
                bounds.union(point.x,point.y);
            }
        }
        TextPosition position(PDPage page,int dir,char symbol) {
            PDRectangle crop=page.getCropBox();
            float x=(dir==180 || dir==270 ? bounds.right : bounds.left)-crop.getLowerLeftX();
            float y=(dir==0 || dir==270 ? bounds.bottom : bounds.top)-crop.getLowerLeftY();
            Matrix m=Matrix.getRotateInstance(Math.toRadians(dir),x,y);
            PointF end=m.transformPoint(1,0);
            return new TextPosition(page.getRotation(),crop.getWidth(),crop.getHeight(),m,end.x,end.y,
                    1,1,1,String.valueOf(symbol),new int[]{symbol},null,1,1);
        }
    }
    @Override protected void startPage(PDPage page) throws IOException {
        placements.clear(); symbols.clear(); positions=0; inlineBytes=0;
        super.startPage(page);
    }
    @Override protected void processTextPosition(TextPosition p) {
        if (positions++<16384) super.processTextPosition(p);
        else out.more=true;
    }
    @Override protected void endPage(PDPage page) throws IOException {
        super.endPage(page);
        if (positions>16384) throw new Documents.Full();
    }
    @Override protected void processOperator(Operator op,List<COSBase> operands) throws IOException {
        PDResources resources=getResources();
        if (resources!=null && out.canTakeImage() && placements.size()<2048) {
            ImageSource image=null;
            if ("Do".equals(op.getName()) && !operands.isEmpty() && operands.get(0) instanceof COSName) {
                COSName name=(COSName)operands.get(0);
                if (resources.isImageXObject(name)) {
                    COSBase object=resources.getCOSObject().getDictionaryObject(COSName.XOBJECT);
                    COSBase stream=object instanceof COSDictionary ? ((COSDictionary)object).getDictionaryObject(name) : null;
                    if (stream instanceof COSDictionary && eligible((COSDictionary)stream))
                        image=()->(PDImage)resources.getXObject(name);
                }
            } else if ("BI".equals(op.getName()) && op.getImageData()!=null && eligible(op.getImageParameters()) &&
                    op.getImageData().length<=Documents.MAX_INPUT-inlineBytes) {
                inlineBytes+=op.getImageData().length;
                image=()->new PDInlineImage(op.getImageParameters(),op.getImageData(),resources);
            }
            if (image!=null) placements.add(new Placement(getGraphicsState().getCurrentTransformationMatrix(),image,resources));
        }
        // PDFBox handles CTMs, nested Form XObjects and text operators.
        super.processOperator(op,operands);
    }
    private static boolean eligible(COSDictionary d) {
        return d!=null && !d.getBoolean(COSName.IMAGE_MASK,COSName.IM,false)
                && Math.min(d.getInt(COSName.WIDTH,COSName.W),d.getInt(COSName.HEIGHT,COSName.H))>=Photos.MIN_EMBEDDED_EDGE;
    }
    @Override protected void writePage() throws IOException {
        // Put zero-content anchors through the same line/word positioning as real glyphs.
        // Use only private-use characters absent from this page, even in custom fonts.
        if (!placements.isEmpty()) {
            List<TextPosition> glyphs=getCharactersByArticle().get(0);
            int dir=glyphs.isEmpty() ? 0 : (int)glyphs.get(0).getDir();
            BitSet used=new BitSet();
            for (TextPosition p:glyphs) {
                String s=p.getUnicode();
                for (int i=0;i<s.length();i++) if (s.charAt(i)>=0xe000 && s.charAt(i)<=0xf8ff) used.set(s.charAt(i)-0xe000);
            }
            int next=0;
            for (Placement placement:placements) {
                next=used.nextClearBit(next);
                if (next>0x18ff) break;
                char symbol=(char)(0xe000+next++);
                symbols.put(symbol,placement);
                glyphs.add(placement.position(getCurrentPage(),dir,symbol));
            }
        }
        setSortByPosition(!placements.isEmpty());
        super.writePage();
    }
    @Override protected void writeString(String text) throws IOException {
        int start=0;
        for (int i=0;!symbols.isEmpty() && i<text.length();i++) {
            Placement source=symbols.get(text.charAt(i));
            if (source==null) continue;
            out.write(text,start,i-start);
            if (out.canTakeImage()) out.image(save(source));
            start=i+1;
        }
        out.write(text,start,text.length()-start);
    }
    private String save(Placement source) throws IOException {
        Bitmap bitmap;
        try {
            PDImage image=source.image.open();
            COSDictionary d=(COSDictionary)image.getCOSObject();
            boolean plain=!d.containsKey(COSName.SMASK) && !d.containsKey(COSName.MASK) && image.getDecode()==null;
            if ("jpg".equals(image.getSuffix()) && plain) {
                try (InputStream in=image.createInputStream(Arrays.asList("DCTDecode","DCT"))) {
                    return Photos.importEmbedded(ctx,in);
                }
            }
            PDColorSpace color=image.getColorSpace();
            if (plain && image.getBitsPerComponent()==8 && (color instanceof PDDeviceRGB || color instanceof PDDeviceGray)
                    && (long)image.getWidth()*image.getHeight()<=128L*1024*1024/color.getNumberOfComponents()) {
                bitmap=readRows(image,color.getNumberOfComponents());
            } else {
                bitmap=renderImage(image,source.resources);
            }
        } catch (IOException | RuntimeException unsupported) { return null; }
        if (bitmap==null) return null;
        try { return Photos.saveJpeg(ctx,bitmap); } finally { bitmap.recycle(); }
    }
    private static Bitmap readRows(PDImage image,int channels) throws IOException {
        int w=image.getWidth(), h=image.getHeight();
        float scale=Math.min(1f,(float)Photos.LONG_EDGE/Math.max(w,h));
        int width=Math.max(1,Math.round(w*scale)), height=Math.max(1,Math.round(h*scale));
        byte[] row=new byte[w*channels];
        int[] pixels=new int[width];
        try (DataInputStream in=new DataInputStream(image.createInputStream())) {
            Bitmap result=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            try {
                int nextRow=0;
                for (int y=0;y<height;y++) {
                    int sourceY=(int)((long)y*h/height);
                    while (nextRow<=sourceY) { in.readFully(row); nextRow++; }
                    for (int x=0;x<width;x++) {
                        int i=(int)((long)x*w/width)*channels, r=row[i]&255;
                        pixels[x]=channels==1 ? Color.rgb(r,r,r) : Color.rgb(r,row[i+1]&255,row[i+2]&255);
                    }
                    result.setPixels(pixels,0,width,0,y,width,1);
                }
                result.setHasAlpha(false);
                return result;
            } catch (IOException | RuntimeException e) { result.recycle(); throw e; }
        }
    }
    private Bitmap renderImage(PDImage image,PDResources original) throws IOException {
        // Let PDFium apply palettes, masks and color transforms to this image alone.
        // The source page's text, paths and other objects never enter the temporary page.
        float ratio=Math.min(1f,(float)Photos.LONG_EDGE/Math.max(image.getWidth(),image.getHeight()));
        int w=Math.max(1,Math.round(image.getWidth()*ratio)), h=Math.max(1,Math.round(image.getHeight()*ratio));
        File tmp=File.createTempFile("picture-",".pdf",Documents.scratch(ctx));
        try {
            try (PDDocument isolated=new PDDocument()) {
                PDPage page=new PDPage(new PDRectangle(w,h)); isolated.addPage(page);
                PDResources resources=new PDResources();
                COSBase colors=original.getCOSObject().getDictionaryObject(COSName.COLORSPACE);
                if (colors instanceof COSDictionary)
                    resources.getCOSObject().setItem(COSName.COLORSPACE,new COSDictionary((COSDictionary)colors));
                page.setResources(resources);
                try (PDPageContentStream content=new PDPageContentStream(isolated,page)) {
                    if (image instanceof PDImageXObject) content.drawImage((PDImageXObject)image,0,0,w,h);
                    else {
                        PDColorSpace cs=image.getColorSpace();
                        resources.put(COSName.getPDFName(cs.getName()),cs);
                        content.drawImage((PDInlineImage)image,0,0,w,h);
                    }
                }
                isolated.save(tmp);
            }
            try (ParcelFileDescriptor fd=ParcelFileDescriptor.open(tmp,ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer pdf=new PdfRenderer(fd); PdfRenderer.Page page=pdf.openPage(0)) {
                return Documents.renderPage(page,w,h);
            }
        } finally { tmp.delete(); }
    }
}
