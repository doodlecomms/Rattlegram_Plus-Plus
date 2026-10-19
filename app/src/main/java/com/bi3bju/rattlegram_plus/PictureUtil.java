package com.bi3bju.rattlegram_plus;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Android-side picture helpers: loading, shrinking, WebP compression and app-private storage. */
final class PictureUtil {
    /** Picture sizes offered when sending: longest side in pixels and WebP quality. */
    static final int[] MAX_SIDE = {160, 240, 320};
    static final int[] QUALITY = {40, 45, 50};

    private static final String DIR = "pictures";

    private PictureUtil() {}

    /** Loads a picked picture, upright, no larger than about maxSide pixels on its longest side. */
    static Bitmap loadUri(Context context, Uri uri, int maxSide) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), uri);
            // ImageDecoder applies the EXIF rotation itself
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                float scale = Math.min(1f, (float) maxSide / Math.max(w, h));
                decoder.setTargetSize(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (opts.inSampleSize * 2) >= maxSide)
            opts.inSampleSize *= 2;
        Bitmap bitmap;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bitmap == null)
            throw new IllegalStateException("not a picture");
        int degrees = 0;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in != null) {
                int o = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                degrees = o == ExifInterface.ORIENTATION_ROTATE_90 ? 90
                        : o == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                        : o == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            }
        } catch (Exception ignored) {
        }
        if (degrees != 0) {
            Matrix m = new Matrix();
            m.postRotate(degrees);
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
        }
        return bitmap;
    }

    /** Shrinks to maxSide pixels on the longest side and compresses as lossy WebP. */
    @SuppressWarnings("deprecation")
    static byte[] compress(Bitmap source, int maxSide, int quality) {
        float scale = Math.min(1f, (float) maxSide / Math.max(source.getWidth(), source.getHeight()));
        Bitmap scaled = scale < 1f
                ? Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * scale)),
                Math.max(1, Math.round(source.getHeight() * scale)), true)
                : source;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Bitmap.CompressFormat format = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
        scaled.compress(format, quality, out);
        return out.toByteArray();
    }

    static Bitmap decode(byte[] bytes) {
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    static File dir(Context context) {
        File d = new File(context.getFilesDir(), DIR);
        if (!d.exists())
            d.mkdirs();
        return d;
    }

    static void save(Context context, String name, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(dir(context), name))) {
            out.write(bytes);
        }
    }

    static byte[] read(Context context, String name) {
        File f = new File(dir(context), name);
        if (!f.exists())
            return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0)
                off += n;
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    static Bitmap load(Context context, String name) {
        byte[] b = read(context, name);
        return b == null ? null : decode(b);
    }

    static void saveText(Context context, String name, String text) throws Exception {
        save(context, name, text.getBytes(StandardCharsets.UTF_8));
    }

    static String readText(Context context, String name) {
        byte[] b = read(context, name);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    /** Deletes every stored picture whose name does not appear in keep. */
    static void prune(Context context, Set<String> keep) {
        File[] files = dir(context).listFiles();
        if (files == null)
            return;
        for (File f : files) {
            String base = f.getName().replaceAll("\\.(webp|pkt)$", "");
            if (!keep.contains(base))
                f.delete();
        }
    }

    /** Copies a stored picture to the phone's Pictures/Rattlegram++ folder (Android 10+). */
    static boolean saveToGallery(Context context, String name) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            return false;
        byte[] bytes = read(context, name);
        if (bytes == null)
            return false;
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/webp");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Rattlegram++");
        Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null)
            return false;
        try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            if (out == null)
                return false;
            out.write(bytes);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
