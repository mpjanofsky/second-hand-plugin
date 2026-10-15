// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin.protocol;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Reads fixtures/golden-frames.txt (generated from the protocol reference implementation). */
final class Golden {
    static final class Entry {
        final String name;
        final String params;
        final byte[] frame;

        Entry(String name, String params, byte[] frame) {
            this.name = name;
            this.params = params;
            this.frame = frame;
        }

        String param(String key) {
            for (String part : params.split(" ")) {
                if (part.startsWith(key + "=")) {
                    return part.substring(key.length() + 1);
                }
            }
            throw new IllegalStateException(name + " has no " + key);
        }
    }

    static List<Entry> load() throws IOException {
        List<Entry> out = new ArrayList<Entry>();
        for (String line : Files.readAllLines(Paths.get("fixtures/golden-frames.txt"), Charset.forName("UTF-8"))) {
            if (line.trim().isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split(" \\| ");
            out.add(new Entry(parts[0], parts[1], hex(parts[2])));
        }
        return out;
    }

    static Entry get(String name) throws IOException {
        for (Entry e : load()) {
            if (e.name.equals(name)) {
                return e;
            }
        }
        throw new IllegalStateException("no golden entry " + name);
    }

    static byte[] hex(String text) {
        String[] parts = text.trim().split(" ");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return out;
    }

    static String hexOf(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            s.append(String.format("%02x ", x & 0xFF));
        }
        return s.toString().trim();
    }

    /** The payload inside a golden frame: drop F0 02 00 seq and the checksum and F1, then unescape. */
    static byte[] payloadOf(byte[] frame) {
        Deframer d = new Deframer();
        List<Frame> frames = d.feed(frame, frame.length);
        if (frames.size() != 1) {
            throw new IllegalStateException("not one frame");
        }
        return frames.get(0).payload;
    }
}
