// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

/** Java fixture because Android creates test-APK providers before instrumentation adds Kotlin. */
public final class WallpaperTestContentProvider extends ContentProvider {
    private static final byte[] PNG_SIGNATURE = new byte[] {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (WallpaperTestDocumentsProvider.METHOD_RESET.equals(method)) {
            return WallpaperTestDocumentsProvider.resetState(getContext());
        }
        if (WallpaperTestDocumentsProvider.METHOD_SET_AVAILABLE.equals(method)) {
            boolean available = extras != null
                && extras.getBoolean(WallpaperTestDocumentsProvider.KEY_AVAILABLE, false);
            return WallpaperTestDocumentsProvider.setAvailable(getContext(), available);
        }
        if (WallpaperTestDocumentsProvider.METHOD_SNAPSHOT.equals(method)) {
            return WallpaperTestDocumentsProvider.snapshotState(getContext());
        }
        return super.call(method, arg, extras);
    }

    @Override
    public String getType(Uri uri) {
        String path = uri.getLastPathSegment();
        if (
                "valid.png".equals(path)
                        || "cold-restart.png".equals(path)
                        || "rotated.png".equals(path)
                        || "animated.png".equals(path)
                        || "maximum-noise.png".equals(path)
                        || "apng-fctl.png".equals(path)
                        || "apng-fdat.png".equals(path)
                        || "corrupt-idat.png".equals(path)
                        || "source-exact-known.png".equals(path)
                        || "source-over-known.png".equals(path)
                        || "source-over-unknown.png".equals(path)
                        || "edge-exact.png".equals(path)
                        || "edge-over.png".equals(path)
                        || "pixels-exact.png".equals(path)
                        || "pixels-over.png".equals(path)
                        || "mime-exact.png".equals(path)
        ) {
            if ("mime-exact.png".equals(path)) return declaredMime(MAXIMUM_MIME_CHARACTERS);
            return "image/png";
        }
        if ("rotated.jpeg".equals(path) || "metadata.jpeg".equals(path)) return "image/jpeg";
        if (
                "truncated.jpeg".equals(path)
                        || "reterminated-truncated.jpeg".equals(path)
                        || "malformed-segment.jpeg".equals(path)
                        || "progressive.jpeg".equals(path)
                        || "extended-sequential.jpeg".equals(path)
                        || "lossless.jpeg".equals(path)
                        || "differential-hierarchical.jpeg".equals(path)
                        || "arithmetic.jpeg".equals(path)
                        || "non-eight-bit.jpeg".equals(path)
        ) return "image/jpeg";
        if ("truncated.png".equals(path)) return "image/png";
        if ("mismatch.png".equals(path)) return "image/jpeg";
        if ("animated.gif".equals(path) || "gif87.gif".equals(path) || "gif89.gif".equals(path)) {
            return "image/gif";
        }
        if (
                "actual.webp".equals(path)
                        || "signature-vp8.webp".equals(path)
                        || "signature-vp8l.webp".equals(path)
                        || "signature-vp8x.webp".equals(path)
        ) return "image/webp";
        if ("signature-heif.heif".equals(path)) return "image/heif";
        if ("signature-heic.heic".equals(path)) return "image/heic";
        if ("signature-avif.avif".equals(path)) return "image/avif";
        if ("lost.png".equals(path)) return "image/png";
        if ("mime-over.png".equals(path)) return declaredMime(MAXIMUM_MIME_CHARACTERS + 1);
        return null;
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        String path = uri.getLastPathSegment();
        if ("source-exact-known.png".equals(path) || "source-over-known.png".equals(path)) {
            File file = knownLengthFixture(path);
            return new AssetFileDescriptor(
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
                0,
                file.length()
            );
        }
        return super.openAssetFile(uri, mode);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        String path = uri.getLastPathSegment();
        if ("lost.png".equals(path)) throw new FileNotFoundException("Synthetic source disappeared");
        if ("mime-over.png".equals(path)) {
            throw new FileNotFoundException("Oversized MIME must fail before opening the source");
        }
        if ("source-exact-known.png".equals(path) || "source-over-known.png".equals(path)) {
            return ParcelFileDescriptor.open(
                knownLengthFixture(path),
                ParcelFileDescriptor.MODE_READ_ONLY
            );
        }
        if ("source-over-unknown.png".equals(path)) {
            return pipe(output -> writeRepeated(output, MAXIMUM_SOURCE_BYTES + 1, (byte) 0x5a));
        }

        byte[] bytes;
        if ("cold-restart.png".equals(path)) {
            bytes = solidColorBitmap(COLD_RESTART_COLOR_ARGB);
        } else if (
                "valid.png".equals(path)
                        || "mismatch.png".equals(path)
                        || "mime-exact.png".equals(path)
        ) {
            bytes = syntheticBitmap(Bitmap.CompressFormat.PNG, 100);
        } else if ("maximum-noise.png".equals(path)) {
            bytes = maximumNoisePng();
        } else if ("rotated.png".equals(path)) {
            bytes = orientedPng();
        } else if ("animated.png".equals(path)) {
            bytes = animatedPng();
        } else if ("animated.gif".equals(path)) {
            bytes = "GIF89a".getBytes(StandardCharsets.US_ASCII);
        } else if ("gif87.gif".equals(path)) {
            bytes = "GIF87a".getBytes(StandardCharsets.US_ASCII);
        } else if ("gif89.gif".equals(path)) {
            bytes = "GIF89a".getBytes(StandardCharsets.US_ASCII);
        } else if ("actual.webp".equals(path)) {
            bytes = syntheticBitmap(
                Build.VERSION.SDK_INT >= 30
                    ? Bitmap.CompressFormat.WEBP_LOSSY
                    : Bitmap.CompressFormat.WEBP,
                90
            );
        } else if ("signature-vp8.webp".equals(path)) {
            bytes = webpSignature("VP8 ");
        } else if ("signature-vp8l.webp".equals(path)) {
            bytes = webpSignature("VP8L");
        } else if ("signature-vp8x.webp".equals(path)) {
            bytes = webpSignature("VP8X");
        } else if ("signature-heif.heif".equals(path)) {
            bytes = isoBaseMediaSignature("mif1", "heif");
        } else if ("signature-heic.heic".equals(path)) {
            bytes = isoBaseMediaSignature("heic", "mif1");
        } else if ("signature-avif.avif".equals(path)) {
            bytes = isoBaseMediaSignature("avif", "mif1");
        } else if ("apng-fctl.png".equals(path)) {
            bytes = injectBeforeIend(
                syntheticBitmap(Bitmap.CompressFormat.PNG, 100),
                pngChunk("fcTL", new byte[26])
            );
        } else if ("apng-fdat.png".equals(path)) {
            bytes = injectBeforeIend(
                syntheticBitmap(Bitmap.CompressFormat.PNG, 100),
                pngChunk("fdAT", new byte[4])
            );
        } else if ("corrupt-idat.png".equals(path)) {
            bytes = corruptIdatPng();
        } else if ("rotated.jpeg".equals(path)) {
            bytes = orientedJpeg();
        } else if ("metadata.jpeg".equals(path)) {
            bytes = metadataJpeg();
        } else if ("truncated.jpeg".equals(path)) {
            byte[] complete = syntheticBitmap(Bitmap.CompressFormat.JPEG, 95);
            bytes = slice(complete, 0, complete.length - 2);
        } else if ("reterminated-truncated.jpeg".equals(path)) {
            byte[] complete = syntheticNoiseJpeg();
            int retained = Math.max(4, complete.length - 96);
            bytes = concat(slice(complete, 0, retained), new byte[] {(byte) 0xff, (byte) 0xd9});
        } else if ("malformed-segment.jpeg".equals(path)) {
            bytes = new byte[] {
                (byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe1,
                0, 1, (byte) 0xff, (byte) 0xd9,
            };
        } else if ("progressive.jpeg".equals(path)) {
            bytes = Base64.decode(PROGRESSIVE_JPEG_BASE64, Base64.DEFAULT);
        } else if ("extended-sequential.jpeg".equals(path)) {
            bytes = jpegWithFrameProcess(0xc1, 8);
        } else if ("lossless.jpeg".equals(path)) {
            bytes = jpegWithFrameProcess(0xc3, 8);
        } else if ("differential-hierarchical.jpeg".equals(path)) {
            bytes = jpegWithFrameProcess(0xc5, 8);
        } else if ("arithmetic.jpeg".equals(path)) {
            bytes = jpegWithFrameProcess(0xc9, 8);
        } else if ("non-eight-bit.jpeg".equals(path)) {
            bytes = jpegWithFrameProcess(0xc0, 12);
        } else if ("truncated.png".equals(path)) {
            byte[] complete = syntheticBitmap(Bitmap.CompressFormat.PNG, 100);
            bytes = slice(complete, 0, complete.length - 6);
        } else if ("edge-exact.png".equals(path)) {
            bytes = transparentPng(8_192, 1);
        } else if ("edge-over.png".equals(path)) {
            bytes = transparentPng(8_193, 1);
        } else if ("pixels-exact.png".equals(path)) {
            bytes = transparentPng(8_000, 5_000);
        } else if ("pixels-over.png".equals(path)) {
            bytes = transparentPng(7_357, 5_437);
        } else {
            throw new FileNotFoundException("Unknown test media");
        }
        return pipe(output -> output.write(bytes));
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    private static byte[] orientedJpeg() throws FileNotFoundException {
        byte[] encoded = syntheticBitmap(Bitmap.CompressFormat.JPEG, 95);
        byte[] exifPayload = concat(
            "Exif".getBytes(StandardCharsets.US_ASCII),
            new byte[] {0, 0},
            new byte[] {
                'I', 'I', 42, 0,
                8, 0, 0, 0,
                1, 0,
                0x12, 0x01, 3, 0,
                1, 0, 0, 0,
                6, 0, 0, 0,
                0, 0, 0, 0,
            }
        );
        int segmentLength = exifPayload.length + 2;
        return concat(
            slice(encoded, 0, 2),
            new byte[] {
                (byte) 0xff,
                (byte) 0xe1,
                (byte) (segmentLength >>> 8),
                (byte) segmentLength,
            },
            exifPayload,
            slice(encoded, 2, encoded.length)
        );
    }

    private static byte[] metadataJpeg() throws FileNotFoundException {
        byte[] encoded = syntheticBitmap(Bitmap.CompressFormat.JPEG, 95);
        byte[] comment = METADATA_SENTINEL.getBytes(StandardCharsets.US_ASCII);
        byte[] exif = concat(
            "Exif".getBytes(StandardCharsets.US_ASCII),
            new byte[] {0, 0},
            new byte[] {
                'I', 'I', 42, 0,
                8, 0, 0, 0,
                1, 0,
                0x12, 0x01, 3, 0,
                1, 0, 0, 0,
                1, 0, 0, 0,
                0, 0, 0, 0,
            },
            METADATA_SENTINEL.getBytes(StandardCharsets.US_ASCII)
        );
        byte[] xmp = concat(
            "http://ns.adobe.com/xap/1.0/".getBytes(StandardCharsets.US_ASCII),
            new byte[] {0},
            ("<x:xmpmeta>" + METADATA_SENTINEL + "</x:xmpmeta>")
                .getBytes(StandardCharsets.US_ASCII)
        );
        return concat(
            slice(encoded, 0, 2),
            jpegSegment(0xfe, comment),
            jpegSegment(0xe1, exif),
            jpegSegment(0xe1, xmp),
            slice(encoded, 2, encoded.length)
        );
    }

    private static byte[] orientedPng() throws FileNotFoundException {
        int[] pixels = new int[] {
            0xffff0000, 0xff00ff00,
            0xff0000ff, 0xffffff00,
            0xff00ffff, 0xffff00ff,
        };
        Bitmap bitmap = Bitmap.createBitmap(pixels, 2, 3, Bitmap.Config.ARGB_8888);
        byte[] encoded;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new FileNotFoundException("Unable to encode oriented PNG fixture");
            }
            encoded = output.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        } finally {
            bitmap.recycle();
        }
        byte[] tiffOrientation = new byte[] {
            'I', 'I', 42, 0,
            8, 0, 0, 0,
            1, 0,
            0x12, 0x01, 3, 0,
            1, 0, 0, 0,
            6, 0, 0, 0,
            0, 0, 0, 0,
        };
        int afterIhdr = 8 + 4 + 4 + 13 + 4;
        return concat(
            slice(encoded, 0, afterIhdr),
            pngChunk("eXIf", tiffOrientation),
            slice(encoded, afterIhdr, encoded.length)
        );
    }

    private static byte[] syntheticBitmap(Bitmap.CompressFormat format, int quality)
            throws FileNotFoundException {
        Bitmap bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!bitmap.compress(format, quality, output)) {
                throw new FileNotFoundException("Unable to encode fixture");
            }
            return output.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        } finally {
            bitmap.recycle();
        }
    }

    private static byte[] solidColorBitmap(int colorArgb) throws FileNotFoundException {
        Bitmap bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(colorArgb);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new FileNotFoundException("Unable to encode solid fixture");
            }
            return output.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        } finally {
            bitmap.recycle();
        }
    }

    private static byte[] syntheticNoiseJpeg() throws FileNotFoundException {
        int[] pixels = new int[64 * 64];
        int state = 0x6d2b79f5;
        for (int index = 0; index < pixels.length; index++) {
            state ^= state << 13;
            state ^= state >>> 17;
            state ^= state << 5;
            pixels[index] = 0xff000000 | (state & 0x00ffffff);
        }
        Bitmap bitmap = Bitmap.createBitmap(pixels, 64, 64, Bitmap.Config.ARGB_8888);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                throw new FileNotFoundException("Unable to encode truncated JPEG fixture");
            }
            return output.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        } finally {
            bitmap.recycle();
        }
    }

    private static byte[] jpegWithFrameProcess(int marker, int precision)
            throws FileNotFoundException {
        byte[] encoded = syntheticBitmap(Bitmap.CompressFormat.JPEG, 95).clone();
        for (int index = 0; index < encoded.length - 4; index++) {
            if ((encoded[index] & 0xff) == 0xff && (encoded[index + 1] & 0xff) == 0xc0) {
                encoded[index + 1] = (byte) marker;
                encoded[index + 4] = (byte) precision;
                return encoded;
            }
        }
        throw new FileNotFoundException("Baseline JPEG frame marker unavailable");
    }

    private static byte[] animatedPng() throws FileNotFoundException {
        byte[] ihdr = concat(
            bigEndianInt(1),
            bigEndianInt(1),
            new byte[] {8, 6, 0, 0, 0}
        );
        byte[] animationControl = concat(bigEndianInt(2), bigEndianInt(0));
        byte[] firstFrameControl = frameControl(0);
        byte[] secondFrameControl = frameControl(1);
        byte[] firstFrame = deflate(new byte[] {0, (byte) 0xff, 0, 0, (byte) 0xff});
        byte[] secondFrame = deflate(new byte[] {0, 0, 0, (byte) 0xff, (byte) 0xff});
        return concat(
            PNG_SIGNATURE,
            pngChunk("IHDR", ihdr),
            pngChunk("acTL", animationControl),
            pngChunk("fcTL", firstFrameControl),
            pngChunk("IDAT", firstFrame),
            pngChunk("fcTL", secondFrameControl),
            pngChunk("fdAT", concat(bigEndianInt(2), secondFrame)),
            pngChunk("IEND")
        );
    }

    private static byte[] frameControl(int sequence) {
        return concat(
            bigEndianInt(sequence),
            bigEndianInt(1),
            bigEndianInt(1),
            bigEndianInt(0),
            bigEndianInt(0),
            new byte[] {0, 1, 0, 10, 0, 0}
        );
    }

    private static byte[] deflate(byte[] source) throws FileNotFoundException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DeflaterOutputStream deflater = new DeflaterOutputStream(bytes)) {
            deflater.write(source);
            deflater.finish();
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new FileNotFoundException("Unable to deflate APNG frame");
        }
    }

    private static String declaredMime(int characters) {
        StringBuilder value = new StringBuilder("image/png;");
        while (value.length() < characters) value.append('x');
        return value.toString();
    }

    private File knownLengthFixture(String path) throws FileNotFoundException {
        int expectedBytes = "source-exact-known.png".equals(path)
            ? MAXIMUM_SOURCE_BYTES
            : MAXIMUM_SOURCE_BYTES + 1;
        File cacheRoot = getContext() == null ? null : getContext().getCacheDir();
        if (cacheRoot == null) throw new FileNotFoundException("Fixture cache unavailable");
        File file = new File(cacheRoot, "aurora-wallpaper-" + path);
        if (file.isFile() && file.length() == expectedBytes) return file;
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            if ("source-exact-known.png".equals(path)) {
                writeExactSizePng(output, expectedBytes);
            } else {
                writeRepeated(output, expectedBytes, (byte) 0x5a);
            }
        } catch (IOException failure) {
            throw new FileNotFoundException("Unable to create known-length fixture");
        }
        if (file.length() != expectedBytes) {
            throw new FileNotFoundException("Known-length fixture has the wrong size");
        }
        return file;
    }

    private static ParcelFileDescriptor pipe(PipeWriter writer) throws FileNotFoundException {
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            Thread thread = new Thread(() -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream output =
                         new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    writer.write(output);
                } catch (IOException ignored) {
                    // Deliberately rejected readers can close before a bounded writer finishes.
                }
            }, "wallpaper-test-provider");
            thread.setDaemon(true);
            thread.start();
            return pipe[0];
        } catch (IOException failure) {
            throw new FileNotFoundException("Unable to create test media pipe");
        }
    }

    private static void writeExactSizePng(OutputStream output, int targetBytes)
            throws IOException, FileNotFoundException {
        byte[] encoded = syntheticBitmap(Bitmap.CompressFormat.PNG, 100);
        int iendOffset = encoded.length - PNG_IEND_CHUNK_BYTES;
        int paddingBytes = targetBytes - encoded.length - PNG_CHUNK_OVERHEAD_BYTES;
        if (iendOffset <= PNG_SIGNATURE.length || paddingBytes < 0) {
            throw new FileNotFoundException("Exact-size PNG target is too small");
        }
        output.write(encoded, 0, iendOffset);
        output.write(bigEndianInt(paddingBytes));
        byte[] type = "ruSt".getBytes(StandardCharsets.US_ASCII);
        output.write(type);
        CRC32 crc = new CRC32();
        crc.update(type);
        byte[] zeros = new byte[8 * 1_024];
        int remaining = paddingBytes;
        while (remaining > 0) {
            int count = Math.min(remaining, zeros.length);
            output.write(zeros, 0, count);
            crc.update(zeros, 0, count);
            remaining -= count;
        }
        output.write(bigEndianInt((int) crc.getValue()));
        output.write(encoded, iendOffset, PNG_IEND_CHUNK_BYTES);
    }

    private static void writeRepeated(OutputStream output, int byteCount, byte value)
            throws IOException {
        byte[] block = new byte[8 * 1_024];
        if (value != 0) java.util.Arrays.fill(block, value);
        int remaining = byteCount;
        while (remaining > 0) {
            int count = Math.min(remaining, block.length);
            output.write(block, 0, count);
            remaining -= count;
        }
    }

    private static byte[] webpSignature(String chunkType) {
        byte[] payload = new byte[10];
        int riffBytes = 4 + 8 + payload.length;
        return concat(
            "RIFF".getBytes(StandardCharsets.US_ASCII),
            littleEndianInt(riffBytes),
            "WEBP".getBytes(StandardCharsets.US_ASCII),
            chunkType.getBytes(StandardCharsets.US_ASCII),
            littleEndianInt(payload.length),
            payload
        );
    }

    private static byte[] isoBaseMediaSignature(String majorBrand, String compatibleBrand) {
        return concat(
            bigEndianInt(24),
            "ftyp".getBytes(StandardCharsets.US_ASCII),
            majorBrand.getBytes(StandardCharsets.US_ASCII),
            new byte[4],
            compatibleBrand.getBytes(StandardCharsets.US_ASCII),
            majorBrand.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private static byte[] injectBeforeIend(byte[] png, byte[] chunk) {
        int iendOffset = png.length - PNG_IEND_CHUNK_BYTES;
        return concat(slice(png, 0, iendOffset), chunk, slice(png, iendOffset, png.length));
    }

    private static byte[] corruptIdatPng() {
        byte[] ihdr = concat(
            bigEndianInt(40),
            bigEndianInt(20),
            new byte[] {8, 6, 0, 0, 0}
        );
        return concat(
            PNG_SIGNATURE,
            pngChunk("IHDR", ihdr),
            pngChunk("IDAT", new byte[] {0x78, (byte) 0x9c, 0, 0, 0, 0}),
            pngChunk("IEND")
        );
    }

    private static synchronized byte[] transparentPng(int width, int height)
            throws FileNotFoundException {
        if (width == 8_192 && height == 1 && edgeExactPng != null) return edgeExactPng;
        if (width == 8_193 && height == 1 && edgeOverPng != null) return edgeOverPng;
        if (width == 8_000 && height == 5_000 && pixelsExactPng != null) return pixelsExactPng;
        if (width == 7_357 && height == 5_437 && pixelsOverPng != null) return pixelsOverPng;

        byte[] row = new byte[1 + width * 4];
        byte[] compressed;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DeflaterOutputStream deflater = new DeflaterOutputStream(bytes)) {
            for (int y = 0; y < height; y++) deflater.write(row);
            deflater.finish();
            compressed = bytes.toByteArray();
        } catch (IOException failure) {
            throw new FileNotFoundException("Unable to encode bounded-dimension fixture");
        }
        byte[] ihdr = concat(
            bigEndianInt(width),
            bigEndianInt(height),
            new byte[] {8, 6, 0, 0, 0}
        );
        byte[] encoded = concat(
            PNG_SIGNATURE,
            pngChunk("IHDR", ihdr),
            pngChunk("IDAT", compressed),
            pngChunk("IEND")
        );
        if (width == 8_192 && height == 1) edgeExactPng = encoded;
        if (width == 8_193 && height == 1) edgeOverPng = encoded;
        if (width == 8_000 && height == 5_000) pixelsExactPng = encoded;
        if (width == 7_357 && height == 5_437) pixelsOverPng = encoded;
        return encoded;
    }

    private static byte[] maximumNoisePng() throws FileNotFoundException {
        int[] pixels = new int[MAXIMUM_FIXTURE_EDGE * MAXIMUM_FIXTURE_EDGE];
        int state = 0x51f15e5d;
        for (int index = 0; index < pixels.length; index++) {
            state ^= state << 13;
            state ^= state >>> 17;
            state ^= state << 5;
            pixels[index] = 0xff000000 | (state & 0x00ffffff);
        }
        Bitmap bitmap = Bitmap.createBitmap(
            pixels,
            MAXIMUM_FIXTURE_EDGE,
            MAXIMUM_FIXTURE_EDGE,
            Bitmap.Config.ARGB_8888
        );
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new FileNotFoundException("Unable to encode maximum-noise fixture");
            }
            return output.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        } finally {
            bitmap.recycle();
        }
    }

    private static byte[] jpegSegment(int marker, byte[] payload) throws FileNotFoundException {
        int segmentLength = payload.length + 2;
        if (segmentLength > 0xffff) throw new FileNotFoundException("JPEG fixture segment too large");
        return concat(
            new byte[] {
                (byte) 0xff,
                (byte) marker,
                (byte) (segmentLength >>> 8),
                (byte) segmentLength,
            },
            payload
        );
    }

    private static byte[] pngChunk(String type) {
        return pngChunk(type, new byte[0]);
    }

    private static byte[] pngChunk(String type, byte[] payload) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(payload);
        long checksum = crc.getValue();
        return concat(
            bigEndianInt(payload.length),
            typeBytes,
            payload,
            bigEndianInt((int) checksum)
        );
    }

    private static byte[] bigEndianInt(int value) {
        return new byte[] {
            (byte) (value >>> 24),
            (byte) (value >>> 16),
            (byte) (value >>> 8),
            (byte) value,
        };
    }

    private static byte[] littleEndianInt(int value) {
        return new byte[] {
            (byte) value,
            (byte) (value >>> 8),
            (byte) (value >>> 16),
            (byte) (value >>> 24),
        };
    }

    private static byte[] slice(byte[] source, int from, int to) {
        byte[] result = new byte[to - from];
        System.arraycopy(source, from, result, 0, result.length);
        return result;
    }

    private static byte[] concat(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) size += part.length;
        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    public static final String METADATA_SENTINEL =
        "AURORA_PRIVATE_METADATA_SENTINEL_34_0522_-118_2437";
    private static final String PROGRESSIVE_JPEG_BASE64 =
        "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYI" +
        "DAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkF" +
        "BQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU" +
        "FBQUFBT/wgARCAAEAAQDAREAAhEBAxEB/8QAFAABAAAAAAAAAAAAAAAAAAAAB//EABUB" +
        "AQEAAAAAAAAAAAAAAAAAAAUH/9oADAMBAAIQAxAAAAELuKv/xAAWEAADAAAAAAAAAAAA" +
        "AAAAAAAAAxT/2gAIAQEAAQUCucf/xAAXEQADAQAAAAAAAAAAAAAAAAAABRZT/9oACAED" +
        "AQE/Aa1tof/EABcRAAMBAAAAAAAAAAAAAAAAAAAFFlP/2gAIAQIBAT8Bi02R/8QAFRAB" +
        "AQAAAAAAAAAAAAAAAAAAADL/2gAIAQEABj8Cp//EABQQAQAAAAAAAAAAAAAAAAAAAAD/" +
        "2gAIAQEAAT8hT//aAAwDAQACAAMAAAAQP//EABcRAAMBAAAAAAAAAAAAAAAAAAAR0fD/" +
        "2gAIAQMBAT8Q1dP/xAAXEQADAQAAAAAAAAAAAAAAAAAAEdHw/9oACAECAQE/EMlD/8QA" +
        "FxAAAwEAAAAAAAAAAAAAAAAAAAHh8P/aAAgBAQABPxDBU//Z";
    private static final int MAXIMUM_SOURCE_BYTES = 16 * 1_024 * 1_024;
    private static final int PNG_CHUNK_OVERHEAD_BYTES = 12;
    private static final int PNG_IEND_CHUNK_BYTES = 12;
    private static final int MAXIMUM_MIME_CHARACTERS = 256;
    private static final int MAXIMUM_FIXTURE_EDGE = 2_048;
    private static final int COLD_RESTART_COLOR_ARGB = 0xff2457d6;
    private static byte[] edgeExactPng;
    private static byte[] edgeOverPng;
    private static byte[] pixelsExactPng;
    private static byte[] pixelsOverPng;

    @FunctionalInterface
    private interface PipeWriter {
        void write(OutputStream output) throws IOException;
    }
}
