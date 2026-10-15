// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** The few protobuf pieces the clock protocol needs: length-delimited and varint fields, and a tolerant reader. */
final class Proto {
    static final Charset UTF8 = Charset.forName("UTF-8");

    private Proto() {}

    /** One field read from a message. {@code bytes} is set for length-delimited fields. */
    static final class Field {
        final int number;
        final int wireType;
        final long value;
        final byte[] bytes;

        Field(int number, int wireType, long value, byte[] bytes) {
            this.number = number;
            this.wireType = wireType;
            this.value = value;
            this.bytes = bytes;
        }
    }

    static byte[] field(int number, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length + 4);
        varint(out, (number << 3) | 2);
        varint(out, value.length);
        out.write(value, 0, value.length);
        return out.toByteArray();
    }

    static byte[] field(int number, String value) {
        return field(number, value.getBytes(UTF8));
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    private static void varint(ByteArrayOutputStream out, long n) {
        while (true) {
            int b = (int) (n & 0x7F);
            n >>>= 7;
            if (n != 0) {
                out.write(b | 0x80);
            } else {
                out.write(b);
                return;
            }
        }
    }

    /** Reads every field of a message. Throws IllegalArgumentException on malformed input. */
    static List<Field> parse(byte[] data) {
        List<Field> fields = new ArrayList<Field>();
        int[] pos = {0};
        while (pos[0] < data.length) {
            long key = readVarint(data, pos);
            int number = (int) (key >>> 3);
            int wire = (int) (key & 7);
            if (number == 0) {
                throw new IllegalArgumentException("field 0");
            }
            if (wire == 0) {
                fields.add(new Field(number, wire, readVarint(data, pos), null));
            } else if (wire == 2) {
                long length = readVarint(data, pos);
                if (length < 0 || length > data.length - pos[0]) {
                    throw new IllegalArgumentException("truncated");
                }
                byte[] bytes = new byte[(int) length];
                System.arraycopy(data, pos[0], bytes, 0, bytes.length);
                pos[0] += bytes.length;
                fields.add(new Field(number, wire, 0, bytes));
            } else if (wire == 1 || wire == 5) {
                int size = wire == 1 ? 8 : 4;
                if (data.length - pos[0] < size) {
                    throw new IllegalArgumentException("truncated");
                }
                long v = 0;
                for (int i = size - 1; i >= 0; i--) {
                    v = (v << 8) | (data[pos[0] + i] & 0xFF);
                }
                pos[0] += size;
                fields.add(new Field(number, wire, v, null));
            } else {
                throw new IllegalArgumentException("wire type " + wire);
            }
        }
        return fields;
    }

    private static long readVarint(byte[] data, int[] pos) {
        long n = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (pos[0] >= data.length) {
                throw new IllegalArgumentException("truncated");
            }
            int b = data[pos[0]++] & 0xFF;
            n |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return n;
            }
        }
        throw new IllegalArgumentException("varint too long");
    }

    static byte[] firstBytes(List<Field> fields, int number) {
        for (Field f : fields) {
            if (f.number == number && f.bytes != null) {
                return f.bytes;
            }
        }
        return null;
    }
}
