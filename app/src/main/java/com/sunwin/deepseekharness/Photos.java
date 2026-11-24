package com.sunwin.deepseekharness;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import java.io.*;
import java.util.UUID;

final class Photos {
    static String importPhoto(Context context, Uri uri) throws IOException {
        ImageDecoder.Source source=ImageDecoder.createSource(context.getContentResolver(),uri);
        Bitmap bitmap=ImageDecoder.decodeBitmap(source,(decoder,info,src) -> {
            int w=info.getSize().getWidth(), h=info.getSize().getHeight();
            float scale=Math.min(1f,1600f/Math.max(w,h));
            decoder.setTargetSize(Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
        });
        File dir=new File(context.getFilesDir(),"photos");
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("photo-dir");
        String path="photos/"+UUID.randomUUID()+".jpg";
        File output=new File(context.getFilesDir(),path);
        Bitmap flat=Bitmap.createBitmap(bitmap.getWidth(),bitmap.getHeight(),Bitmap.Config.ARGB_8888);
        try (FileOutputStream out=new FileOutputStream(output)) {
            Canvas canvas=new Canvas(flat); canvas.drawColor(Color.WHITE); canvas.drawBitmap(bitmap,0,0,null);
            if (!flat.compress(Bitmap.CompressFormat.JPEG,88,out)) throw new IOException("photo-write");
        } catch (IOException e) { output.delete(); throw e; }
        finally { bitmap.recycle(); flat.recycle(); }
        return path;
    }
}
