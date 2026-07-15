// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

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
    public String getType(Uri uri) {
        String path = uri.getLastPathSegment();
        if (
                "valid.png".equals(path)
                        || "rotated.png".equals(path)
                        || "animated.png".equals(path)
                        || "maximum-noise.png".equals(path)
        ) {
            return "image/png";
        }
        if ("rotated.jpeg".equals(path) || "metadata.jpeg".equals(path)) return "image/jpeg";
        if ("truncated.jpeg".equals(path)) return "image/jpeg";
        if ("truncated.png".equals(path)) return "image/png";
        if ("mismatch.png".equals(path)) return "image/jpeg";
        if ("animated.gif".equals(path)) return "image/gif";
        return null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        byte[] bytes;
        String path = uri.getLastPathSegment();
        if ("valid.png".equals(path) || "mismatch.png".equals(path)) {
            bytes = syntheticBitmap(Bitmap.CompressFormat.PNG, 100);
        } else if ("maximum-noise.png".equals(path)) {
            bytes = maximumNoisePng();
        } else if ("rotated.png".equals(path)) {
            bytes = orientedPng();
        } else if ("animated.png".equals(path)) {
            bytes = concat(PNG_SIGNATURE, pngChunk("acTL"), pngChunk("IEND"));
        } else if ("animated.gif".equals(path)) {
            bytes = "GIF89a".getBytes(StandardCharsets.US_ASCII);
        } else if ("rotated.jpeg".equals(path)) {
            bytes = orientedJpeg();
        } else if ("metadata.jpeg".equals(path)) {
            bytes = metadataJpeg();
        } else if ("truncated.jpeg".equals(path)) {
            byte[] complete = syntheticBitmap(Bitmap.CompressFormat.JPEG, 95);
            bytes = slice(complete, 0, complete.length - 2);
        } else if ("truncated.png".equals(path)) {
            byte[] complete = syntheticBitmap(Bitmap.CompressFormat.PNG, 100);
            bytes = slice(complete, 0, complete.length - 6);
        } else {
            throw new FileNotFoundException("Unknown test media");
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            Thread writer = new Thread(() -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream output =
                         new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    output.write(bytes);
                } catch (IOException ignored) {
                    // The reader may close early for deliberately rejected fixtures.
                }
            }, "wallpaper-test-provider");
            writer.setDaemon(true);
            writer.start();
            return pipe[0];
        } catch (IOException failure) {
            throw new FileNotFoundException("Unable to create test media pipe");
        }
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
    private static final int MAXIMUM_FIXTURE_EDGE = 2_048;
}
