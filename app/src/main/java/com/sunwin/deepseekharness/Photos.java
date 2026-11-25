package com.sunwin.deepseekharness;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import java.io.*;

final class Photos {
    static final int LONG_EDGE=1600;
    private static final int MAX_JPEG=768*1024;
    static String importPhoto(Context context, Uri uri) throws IOException {
        ImageDecoder.Source source=ImageDecoder.createSource(context.getContentResolver(),uri);
        Bitmap bitmap=ImageDecoder.decodeBitmap(source,(decoder,info,src) -> {
            int w=info.getSize().getWidth(), h=info.getSize().getHeight();
            float scale=Math.min(1f,(float)LONG_EDGE/Math.max(w,h));
            decoder.setTargetSize(Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
        });
        try {
            if (!bitmap.hasAlpha()) return saveJpeg(context,bitmap);
            Bitmap flat=Bitmap.createBitmap(bitmap.getWidth(),bitmap.getHeight(),Bitmap.Config.ARGB_8888);
            try {
                Canvas canvas=new Canvas(flat); canvas.drawColor(Color.WHITE); canvas.drawBitmap(bitmap,0,0,null);
                return saveJpeg(context,flat);
            } finally { flat.recycle(); }
        } finally { bitmap.recycle(); }
    }
    static String saveJpeg(Context context,Bitmap bitmap) throws IOException {
        String path=Attachment.newPath(context,"photos",".jpg");
        File output=Attachment.file(context,path);
        Bitmap image=bitmap;
        try {
            while (true) {
                try (FileOutputStream out=new FileOutputStream(output)) {
                    if (!image.compress(Bitmap.CompressFormat.JPEG,85,out)) throw new IOException("photo-write");
                }
                if (output.length()<=MAX_JPEG) break;
                // Bound the aggregate JSON payload even for nine high-entropy camera images.
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
