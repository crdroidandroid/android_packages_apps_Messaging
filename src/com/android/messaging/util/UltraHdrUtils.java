/*
 * Copyright (C) 2026 The YAAP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.messaging.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.graphics.Gainmap;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;

import com.android.messaging.datamodel.MediaScratchFileProvider;
import com.android.messaging.util.exif.ExifInterface;
import com.android.messaging.util.exif.ExifInterface.OrientationParams;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Converts Ultra HDR (gain map) images into plain SDR JPEGs for MMS. */
public final class UltraHdrUtils {
    private static final String TAG = LogUtil.BUGLE_IMAGE_TAG;
    private static final int PROBE_MAX_DIMENSION = 128;
    private static final int OETF_LUT_SIZE = 1024;
    private static final float DECODE_SCALE_SLOP = 1.5f;
    private static final float MIN_RATIO = 1e-4f;

    public static final int DEFAULT_JPEG_QUALITY = 95;

    private UltraHdrUtils() {
    }

    public static final class ConvertedImage {
        public final byte[] bytes;
        public final int width;
        public final int height;

        ConvertedImage(final byte[] bytes, final int width, final int height) {
            this.bytes = bytes;
            this.width = width;
            this.height = height;
        }
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }

    public static boolean hasGainmap(final Bitmap bitmap) {
        return isSupported() && bitmap != null && bitmap.hasGainmap();
    }

    public static void stripGainmap(final Bitmap bitmap) {
        if (isSupported() && bitmap != null) {
            bitmap.setGainmap(null);
        }
    }

    public static boolean hasGainmap(final Context context, final Uri uri) {
        if (!isSupported() || context == null || uri == null) {
            return false;
        }
        Bitmap probe = null;
        try {
            probe = decode(context, uri, PROBE_MAX_DIMENSION);
            return probe != null && probe.hasGainmap();
        } finally {
            if (probe != null) {
                probe.recycle();
            }
        }
    }

    /** Returns an SDR JPEG for the image, or null when it has no gain map or conversion fails. */
    public static ConvertedImage convertToSdrJpeg(final Context context, final Uri uri,
            final int maxWidth, final int maxHeight, final int quality) {
        if (!isSupported() || context == null || uri == null || maxWidth <= 0 || maxHeight <= 0) {
            return null;
        }
        if (!hasGainmap(context, uri)) {
            return null;
        }
        Bitmap work = null;
        try {
            work = decode(context, uri, Math.max(maxWidth, maxHeight));
            if (work == null || !work.hasGainmap()) {
                return null;
            }
            final Bitmap oriented = orient(context, uri, work);
            if (oriented != null && oriented != work) {
                work.recycle();
                work = oriented;
            }
            final Bitmap tonemapped = applyGainmap(work);
            if (tonemapped == null) {
                return null;
            }
            work.recycle();
            work = tonemapped;
            final Bitmap scaled = scaleToLimits(work, maxWidth, maxHeight);
            if (scaled != null && scaled != work) {
                work.recycle();
                work = scaled;
            }
            final byte[] bytes = ImageUtils.bitmapToBytes(work, quality);
            if (bytes == null) {
                return null;
            }
            return new ConvertedImage(bytes, work.getWidth(), work.getHeight());
        } catch (Exception | OutOfMemoryError e) {
            LogUtil.w(TAG, "Failed to convert Ultra HDR image " + uri, e);
            return null;
        } finally {
            if (work != null) {
                work.recycle();
            }
        }
    }

    /** Applies the gain map and returns a new SDR bitmap, or null on failure. */
    public static Bitmap tonemapToSdr(final Bitmap base) {
        if (!isSupported() || base == null || !base.hasGainmap()) {
            return null;
        }
        return applyGainmap(base);
    }

    public static Uri writeToScratchSpace(final byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        final Uri uri = MediaScratchFileProvider.buildMediaScratchSpaceUri("jpg");
        final File file = MediaScratchFileProvider.getFileFromUri(uri);
        if (file == null) {
            return null;
        }
        OutputStream os = null;
        try {
            os = new FileOutputStream(file);
            os.write(bytes);
            return uri;
        } catch (IOException e) {
            LogUtil.w(TAG, "Failed to write converted image", e);
            return null;
        } finally {
            closeQuietly(os);
        }
    }

    public static void deleteScratchFile(final Uri uri) {
        if (uri == null || !MediaScratchFileProvider.isMediaScratchSpaceUri(uri)) {
            return;
        }
        final File file = MediaScratchFileProvider.getFileFromUri(uri);
        if (file != null && file.exists() && !file.delete()) {
            LogUtil.w(TAG, "Failed to delete scratch file " + file);
        }
    }

    private static Bitmap decode(final Context context, final Uri uri, final int maxDimension) {
        InputStream is = null;
        try {
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            is = context.getContentResolver().openInputStream(uri);
            if (is == null) {
                return null;
            }
            BitmapFactory.decodeStream(is, null, bounds);
            closeQuietly(is);
            is = null;
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            final int target = Math.max(1, (int) (maxDimension * DECODE_SCALE_SLOP));
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, target);
            is = context.getContentResolver().openInputStream(uri);
            if (is == null) {
                return null;
            }
            return BitmapFactory.decodeStream(is, null, options);
        } catch (Exception | OutOfMemoryError e) {
            LogUtil.w(TAG, "Failed to decode image " + uri, e);
            return null;
        } finally {
            closeQuietly(is);
        }
    }

    private static Bitmap orient(final Context context, final Uri uri, final Bitmap bitmap) {
        final int orientation = ImageUtils.getOrientation(context, uri);
        if (orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_UNDEFINED
                || orientation
                        == androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL) {
            return bitmap;
        }
        final OrientationParams params = ExifInterface.getOrientationParams(orientation);
        if (params.rotation == 0 && params.scaleX == 1 && params.scaleY == 1) {
            return bitmap;
        }
        try {
            final Matrix matrix = new Matrix();
            matrix.postRotate(params.rotation);
            matrix.postScale(params.scaleX, params.scaleY);
            final Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(),
                    bitmap.getHeight(), matrix, true);
            return rotated != null ? rotated : bitmap;
        } catch (Exception | OutOfMemoryError e) {
            LogUtil.w(TAG, "Failed to orient image " + uri, e);
            return bitmap;
        }
    }

    private static Bitmap scaleToLimits(final Bitmap bitmap, final int maxWidth,
            final int maxHeight) {
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        int widthLimit = maxWidth;
        int heightLimit = maxHeight;
        if ((height > width) != (heightLimit > widthLimit)) {
            final int temp = widthLimit;
            widthLimit = heightLimit;
            heightLimit = temp;
        }
        if (width <= widthLimit && height <= heightLimit) {
            return bitmap;
        }
        final float scale = Math.min(widthLimit / (float) width, heightLimit / (float) height);
        final int targetWidth = Math.max(1, Math.round(width * scale));
        final int targetHeight = Math.max(1, Math.round(height * scale));
        try {
            final Bitmap scaled =
                    Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
            return scaled != null ? scaled : bitmap;
        } catch (Exception | OutOfMemoryError e) {
            LogUtil.w(TAG, "Failed to scale image", e);
            return bitmap;
        }
    }

    private static Bitmap applyGainmap(final Bitmap base) {
        final Gainmap gainmap = base.getGainmap();
        if (gainmap == null) {
            return null;
        }
        final Bitmap contents = gainmap.getGainmapContents();
        if (contents == null) {
            return null;
        }
        Bitmap scaledGainmap = null;
        try {
            final int width = base.getWidth();
            final int height = base.getHeight();
            Bitmap gainmapPixels = contents;
            if (contents.getWidth() != width || contents.getHeight() != height) {
                scaledGainmap = Bitmap.createScaledBitmap(contents, width, height, true);
                if (scaledGainmap == null) {
                    return null;
                }
                gainmapPixels = scaledGainmap;
            }
            return renderTonemapped(base, gainmapPixels, gainmap);
        } catch (Exception | OutOfMemoryError e) {
            LogUtil.w(TAG, "Failed to apply gain map", e);
            return null;
        } finally {
            if (scaledGainmap != null) {
                scaledGainmap.recycle();
            }
        }
    }

    private static Bitmap renderTonemapped(final Bitmap base, final Bitmap gainmapPixels,
            final Gainmap gainmap) {
        final int width = base.getWidth();
        final int height = base.getHeight();
        final int[] pixels = new int[width * height];
        final int[] gainPixels = new int[width * height];
        base.getPixels(pixels, 0, width, 0, 0, width, height);
        gainmapPixels.getPixels(gainPixels, 0, width, 0, 0, width, height);

        final float[] ratioMin = gainmap.getRatioMin();
        final float[] ratioMax = gainmap.getRatioMax();
        final float[] gamma = gainmap.getGamma();
        final float[] epsilonSdr = gainmap.getEpsilonSdr();
        final float[] epsilonHdr = gainmap.getEpsilonHdr();
        final float weight = getDirection(gainmap) == Gainmap.GAINMAP_DIRECTION_HDR_TO_SDR
                ? -1f : 1f;

        ColorSpace colorSpace = base.getColorSpace();
        if (!(colorSpace instanceof ColorSpace.Rgb)) {
            colorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
        }
        final ColorSpace.Rgb rgbSpace = (ColorSpace.Rgb) colorSpace;

        final float[] linearLut = new float[256];
        for (int i = 0; i < linearLut.length; i++) {
            final float v = i / 255f;
            linearLut[i] = rgbSpace.toLinear(v, v, v)[0];
        }
        final float[][] gainLut = new float[3][256];
        for (int c = 0; c < gainLut.length; c++) {
            final float min = Math.max(ratioMin[c], MIN_RATIO);
            final float max = Math.max(ratioMax[c], min);
            final float logMin = (float) Math.log(min);
            final float logRange = (float) Math.log(max) - logMin;
            final float channelGamma = gamma[c] > 0f ? gamma[c] : 1f;
            for (int i = 0; i < gainLut[c].length; i++) {
                final float gain = (float) Math.pow(i / 255f, channelGamma);
                gainLut[c][i] = (float) Math.exp((logMin + logRange * gain) * weight);
            }
        }
        final float[] oetfLut = new float[OETF_LUT_SIZE];
        for (int i = 0; i < oetfLut.length; i++) {
            final float v = i / (float) (oetfLut.length - 1);
            oetfLut[i] = rgbSpace.fromLinear(v, v, v)[0];
        }

        final float headroom = Math.max(1f,
                Math.max(ratioMax[0], Math.max(ratioMax[1], ratioMax[2])));
        final boolean tonemap = weight > 0f;
        final boolean gainmapAlpha = gainmapPixels.getConfig() == Bitmap.Config.ALPHA_8;

        for (int i = 0; i < pixels.length; i++) {
            final int pixel = pixels[i];
            final int alpha = (pixel >>> 24) & 0xFF;
            int r = (pixel >>> 16) & 0xFF;
            int g = (pixel >>> 8) & 0xFF;
            int b = pixel & 0xFF;
            if (alpha != 0xFF) {
                final int inverse = 255 - alpha;
                r = (r * alpha + 255 * inverse) / 255;
                g = (g * alpha + 255 * inverse) / 255;
                b = (b * alpha + 255 * inverse) / 255;
            }
            final int gainPixel = gainPixels[i];
            final int gainR;
            final int gainG;
            final int gainB;
            if (gainmapAlpha) {
                final int gain = (gainPixel >>> 24) & 0xFF;
                gainR = gain;
                gainG = gain;
                gainB = gain;
            } else {
                gainR = (gainPixel >>> 16) & 0xFF;
                gainG = (gainPixel >>> 8) & 0xFF;
                gainB = gainPixel & 0xFF;
            }
            float hr = (linearLut[r] + epsilonSdr[0]) * gainLut[0][gainR] - epsilonHdr[0];
            float hg = (linearLut[g] + epsilonSdr[1]) * gainLut[1][gainG] - epsilonHdr[1];
            float hb = (linearLut[b] + epsilonSdr[2]) * gainLut[2][gainB] - epsilonHdr[2];
            final float max = Math.max(hr, Math.max(hg, hb));
            if (tonemap && max > 0f) {
                // Platform reference tonemap (libultrahdr globalTonemap).
                final float scale = reinhard(max, headroom) / max;
                hr *= scale;
                hg *= scale;
                hb *= scale;
            }
            pixels[i] = 0xFF000000
                    | (oetf8(oetfLut, hr) << 16)
                    | (oetf8(oetfLut, hg) << 8)
                    | oetf8(oetfLut, hb);
        }

        final Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, false,
                colorSpace);
        result.setPixels(pixels, 0, width, 0, 0, width, height);
        result.setGainmap(null);
        return result;
    }

    private static int getDirection(final Gainmap gainmap) {
        try {
            return gainmap.getGainmapDirection();
        } catch (UnsupportedOperationException | NoSuchMethodError e) {
            return Gainmap.GAINMAP_DIRECTION_SDR_TO_HDR;
        }
    }

    private static float reinhard(final float y, final float headroom) {
        return ((1f + y / (headroom * headroom)) / (1f + y)) * y;
    }

    private static int oetf8(final float[] lut, final float value) {
        final float finite = Float.isFinite(value) ? value : 0f;
        final float clamped = Math.max(0f, Math.min(1f, finite));
        final int index = Math.min(lut.length - 1, (int) (clamped * (lut.length - 1) + 0.5f));
        return Math.max(0, Math.min(255, (int) (lut[index] * 255f + 0.5f)));
    }

    private static int sampleSizeFor(final int width, final int height, final int maxDimension) {
        int sampleSize = 1;
        while (width / sampleSize > maxDimension || height / sampleSize > maxDimension) {
            sampleSize *= 2;
            if (sampleSize >= Integer.MAX_VALUE / 2) {
                break;
            }
        }
        return sampleSize;
    }

    private static void closeQuietly(final Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
            }
        }
    }
}
