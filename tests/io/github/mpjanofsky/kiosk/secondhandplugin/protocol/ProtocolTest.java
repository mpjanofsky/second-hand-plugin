// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin.protocol;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

/** The framing, message builders, time format and token against golden bytes generated from agt.py. */
public final class ProtocolTest {
    public static void main(String[] args) throws Exception {
        messagesMatchGolden();
        framingMatchesGolden();
        deframerHandlesSplitsAndNoise();
        deframerDropsOversizeAndResyncs();
        responseIsRecognised();
        timeFormat();
        tokenIsStable();
        System.out.println("ProtocolTest ok");
    }

    private static void check(Golden.Entry e, byte[] payload, Framer framer) {
        byte[] got = framer.frame(payload);
        assert Arrays.equals(got, e.frame) : e.name + "\n got " + Golden.hexOf(got) + "\nwant " + Golden.hexOf(e.frame);
    }

    private static int seq(Golden.Entry e) {
        return Integer.parseInt(e.param("seq"));
    }

    private static void messagesMatchGolden() throws Exception {
        check(Golden.get("discover"), Messages.discover(), new Framer(1));
        check(Golden.get("timeinfo"), Messages.timeInfo(Golden.get("timeinfo").param("time")), new Framer(2));
        check(Golden.get("clear-indicator"), Messages.clearIndicator(), new Framer(3));
        Golden.Entry set = Golden.get("set-alert");
        check(set, Messages.setTimer(set.param("token"), set.param("end")), new Framer(4));
        check(Golden.get("timers-active"), Messages.timersActive(), new Framer(5));
        check(Golden.get("delete-alert"), Messages.deleteAlert("1234567890"), new Framer(6));
        check(Golden.get("timers-cleared"), Messages.timersCleared(), new Framer(7));
        Golden.Entry derived = Golden.get("set-alert-derived-token");
        String token = AlertToken.fromTimerId(derived.param("timerId"));
        assert token.equals(derived.param("token")) : token;
        check(derived, Messages.setTimer(token, derived.param("end")), new Framer(8));
    }

    private static void framingMatchesGolden() throws Exception {
        byte[] raw = {0x01, (byte) 0xF0, 0x02, (byte) 0xF1, 0x03, (byte) 0xF2, 0x04};
        check(Golden.get("escape-payload"), raw, new Framer(1));
        check(Golden.get("escape-checksum-low"), new byte[] {(byte) 0xEE}, new Framer(1));
        byte[] big = new byte[241];
        Arrays.fill(big, (byte) 0xFF);
        big[240] = (byte) 0xEE;
        check(Golden.get("escape-checksum-high"), big, new Framer(1));
        Framer f = new Framer(0xEF);
        check(Golden.get("seq-before-reserved"), new byte[] {0}, f);
        check(Golden.get("seq-skips-reserved"), new byte[] {0}, f);
        // Sequence wraps 0xFF -> 0x00 and never lands on a reserved value.
        Framer w = new Framer(0xFF);
        w.frame(new byte[] {0});
        assert (w.frame(new byte[] {0})[3] & 0xFF) == 0 : "wrap";
    }

    private static void deframerHandlesSplitsAndNoise() throws Exception {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(new byte[] {0x55, (byte) 0xF1, 0x12}, 0, 3);          // noise outside a frame
        for (Golden.Entry e : Golden.load()) {
            if (e.name.startsWith("escape-checksum-high") || e.name.equals("seq-skips-reserved") || e.name.equals("seq-before-reserved")) {
                continue;
            }
            all.write(e.frame, 0, e.frame.length);
        }
        byte[] bytes = all.toByteArray();
        int expected = 0;
        for (Golden.Entry e : Golden.load()) {
            if (!(e.name.startsWith("escape-checksum-high") || e.name.equals("seq-skips-reserved") || e.name.equals("seq-before-reserved"))) {
                expected++;
            }
        }
        // Every possible split point: two reads.
        for (int cut = 0; cut <= bytes.length; cut++) {
            Deframer d = new Deframer();
            List<Frame> got = d.feed(Arrays.copyOfRange(bytes, 0, cut), cut);
            got.addAll(d.feed(Arrays.copyOfRange(bytes, cut, bytes.length), bytes.length - cut));
            assert got.size() == expected : "cut " + cut + " got " + got.size();
            for (Frame fr : got) {
                assert fr.checksumOk : "cut " + cut;
            }
        }
        // One byte at a time.
        Deframer d = new Deframer();
        int count = 0;
        for (byte b : bytes) {
            count += d.feed(new byte[] {b}, 1).size();
        }
        assert count == expected : count;
        // A flipped payload byte is reported as a bad checksum, not dropped silently.
        byte[] bad = Golden.get("discover").frame.clone();
        bad[10] ^= 0x01;
        Frame frame = new Deframer().feed(bad, bad.length).get(0);
        assert !frame.checksumOk;
    }

    private static void deframerDropsOversizeAndResyncs() throws Exception {
        Deframer d = new Deframer();
        byte[] junk = new byte[Deframer.MAX_FRAME + 500];
        Arrays.fill(junk, (byte) 0x41);
        junk[0] = (byte) 0xF0;
        assert d.feed(junk, junk.length).isEmpty();
        byte[] good = Golden.get("discover").frame;
        assert d.feed(good, good.length).size() == 1 : "recovers after oversize";
        // A new F0 inside a frame starts over; the half frame is lost, the next one is read.
        byte[] half = {(byte) 0xF0, 0x02, 0x00, 0x01, 0x0a};
        byte[] mixed = new byte[half.length + good.length];
        System.arraycopy(half, 0, mixed, 0, half.length);
        System.arraycopy(good, 0, mixed, half.length, good.length);
        List<Frame> got = new Deframer().feed(mixed, mixed.length);
        assert got.size() == 1 && got.get(0).checksumOk;
    }

    private static void responseIsRecognised() throws Exception {
        byte[] frame = Golden.get("inbound-discover-response").frame;
        Frame f = new Deframer().feed(frame, frame.length).get(0);
        assert f.checksumOk && f.seq == 0;
        assert Messages.isDiscoverResponse(f.payload);
        assert !Messages.isDiscoverResponse(Golden.payloadOf(Golden.get("timers-active").frame));
        assert Messages.parse(new byte[0]) == null;
        assert Messages.parse(new byte[] {0x0a, 0x7f}) == null : "truncated";
        assert Messages.parse(new byte[] {(byte) 0xff, (byte) 0xff}) == null : "garbage";
        Messages.Incoming in = Messages.parse(Golden.payloadOf(Golden.get("timeinfo").frame));
        assert in.is("Alexa.Gadget.StateListener", "StateUpdate");
    }

    private static void timeFormat() {
        TimeZone ny = TimeZone.getTimeZone("America/New_York");
        // 2026-10-14 12:00:00 EDT = 16:00 UTC
        assert ClockTime.format(1791993600000L, ny).equals("2026-10-14T12:00:00-04:00") : ClockTime.format(1791993600000L, ny);
        // DST ends 2026-11-01 at 02:00 EDT: 05:30 UTC is 01:30 EDT, 06:30 UTC is 01:30 EST.
        long edt = 1793511000000L;
        assert ClockTime.format(edt, ny).equals("2026-11-01T01:30:00-04:00") : ClockTime.format(edt, ny);
        assert ClockTime.format(edt + 3600000L, ny).equals("2026-11-01T01:30:00-05:00") : ClockTime.format(edt + 3600000L, ny);
        assert ClockTime.format(edt, ny).length() == 25;
        assert ClockTime.format(0L, TimeZone.getTimeZone("Asia/Kolkata")).equals("1970-01-01T05:30:00+05:30");
        assert ClockTime.format(0L, TimeZone.getTimeZone("UTC")).equals("1970-01-01T00:00:00+00:00");
    }

    private static void tokenIsStable() {
        String a = AlertToken.fromTimerId("01JAAAAAAAAAAAAAAAAAAAAAAA");
        assert a.equals(AlertToken.fromTimerId("01JAAAAAAAAAAAAAAAAAAAAAAA"));
        assert !a.equals(AlertToken.fromTimerId("01JBBBBBBBBBBBBBBBBBBBBBBB"));
        for (int i = 0; i < 200; i++) {
            String t = AlertToken.fromTimerId("timer-" + i);
            assert t.length() == 10 && t.charAt(0) != '0' : t;
        }
    }
}
