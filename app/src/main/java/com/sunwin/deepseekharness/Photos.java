package com.sunwin.deepseekharness;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import java.io.*;

final class Photos {
    static final int LONG_EDGE=1600, MIN_EMBEDDED_EDGE=64;
    private static final int MAX_JPEG=768*1024;
    static String importPhoto(Context context, Uri uri) throws IOException {
        Bitmap bitmap=decode(ImageDecoder.createSource(context.getContentResolver(),uri),false);
        try { return saveJpeg(context,bitmap); } finally { bitmap.recycle(); }
    }
    private static final class SmallImage extends RuntimeException {}
    private static Bitmap decode(ImageDecoder.Source source,boolean embedded) throws IOException {
        return ImageDecoder.decodeBitmap(source,(decoder,info,src) -> {
            int w=info.getSize().getWidth(), h=info.getSize().getHeight();
            if (embedded && Math.min(w,h)<MIN_EMBEDDED_EDGE) throw new SmallImage();
            float scale=Math.min(1f,(float)LONG_EDGE/Math.max(w,h));
            decoder.setTargetSize(Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
        });
    }
    static String importEmbedded(Context context,InputStream in) throws IOException {
        File tmp=File.createTempFile("image-",".part",Documents.scratch(context));
        try {
            try (OutputStream output=new FileOutputStream(tmp)) {
                byte[] bytes=new byte[8192]; int total=0,n;
                while ((n=in.read(bytes))!=-1) {
                    if ((total+=n)>Documents.MAX_INPUT) return null;
                    output.write(bytes,0,n);
                }
            }
            Bitmap bitmap;
            try { bitmap=decode(ImageDecoder.createSource(tmp),true); }
            catch (ImageDecoder.DecodeException | SmallImage unsupported) { return null; }
            try { return saveJpeg(context,bitmap); } finally { bitmap.recycle(); }
        } finally { tmp.delete(); }
    }
    static String saveJpeg(Context context,Bitmap bitmap) throws IOException {
        String path=Attachment.newPath(context,"photos",".jpg");
        File output=Attachment.file(context,path);
        Bitmap image=bitmap;
        try {
            float scale=Math.min(1f,(float)LONG_EDGE/Math.max(bitmap.getWidth(),bitmap.getHeight()));
            if (bitmap.hasAlpha() || scale<1) {
                int w=Math.max(1,Math.round(bitmap.getWidth()*scale)), h=Math.max(1,Math.round(bitmap.getHeight()*scale));
                image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
                Canvas canvas=new Canvas(image);
                canvas.drawColor(Color.WHITE);
                canvas.drawBitmap(bitmap,null,new Rect(0,0,w,h),new Paint(Paint.FILTER_BITMAP_FLAG));
            }
            while (true) {
                try (FileOutputStream out=new FileOutputStream(output)) {
                    if (!image.compress(Bitmap.CompressFormat.JPEG,85,out)) throw new IOException("photo-write");
                }
                if (output.length()<=MAX_JPEG) break;
                // Bound each JPEG before base64 encoding the combined prompt.
                double ratio=0.9*Math.sqrt((double)MAX_JPEG/output.length());
                int w=Math.max(1,(int)(image.getWidth()*ratio)), h=Math.max(1,(int)(image.getHeight()*ratio));
                Bitmap smaller=Bitmap.createScaledBitmap(image,w,h,true);
                if (image!=bitmap) image.recycle();
                image=smaller;
            }
        } catch (IOException | RuntimeException e) { output.delete(); throw e; }
        finally { if (image!=bitmap) image.recycle(); }
        return path;
    }
    static Bitmap load(Context context,String path,int edge) throws IOException {
        String local=Attachment.file(context,path).getPath();
        BitmapFactory.Options opt=new BitmapFactory.Options();
        opt.inJustDecodeBounds=true; BitmapFactory.decodeFile(local,opt);
        int size=Math.max(opt.outHeight,opt.outWidth);
        opt.inSampleSize=1;
        while (size / opt.inSampleSize / 2 >= edge) opt.inSampleSize*=2;
        opt.inJustDecodeBounds=false;
        Bitmap image=BitmapFactory.decodeFile(local,opt);
        if (image==null) throw new IOException("photo-decode");
        int longest=Math.max(image.getWidth(),image.getHeight());
        if (longest<=edge) return image;
        float ratio=(float)edge/longest;
        Bitmap scaled=Bitmap.createScaledBitmap(image,Math.max(1,Math.round(image.getWidth()*ratio)),
                Math.max(1,Math.round(image.getHeight()*ratio)),true);
        image.recycle();
        return scaled;
    }
}
