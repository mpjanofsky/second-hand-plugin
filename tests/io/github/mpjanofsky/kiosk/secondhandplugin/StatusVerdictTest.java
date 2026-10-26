// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin;

/** The Overview tile: each link state gets its level, nothing is shown with no clock paired, and the text is the status line cut to 80 characters. */
public final class StatusVerdictTest {
    public static void main(String[] args) {
        assert StatusVerdict.level(ClockLink.State.CONNECTED, true).equals("on");
        assert StatusVerdict.level(ClockLink.State.RETRYING, true).equals("warn");
        assert StatusVerdict.level(ClockLink.State.UNAVAILABLE, true).equals("off");
        assert StatusVerdict.level(ClockLink.State.CONNECTING, true).equals("");
        // Unpaired: the link says "No clock paired yet", which is a state, not a problem.
        assert StatusVerdict.level(ClockLink.State.UNAVAILABLE, false).equals("");
        assert StatusVerdict.level(ClockLink.State.CONNECTED, false).equals("");

        assert StatusVerdict.text("Connected to the clock.").equals("Connected to the clock.");
        assert StatusVerdict.text("Connected\nto the clock.").equals("Connected to the clock.");
        assert StatusVerdict.text("   ").equals(StatusVerdict.TITLE);
        assert StatusVerdict.text(null).equals(StatusVerdict.TITLE);
        String long100 = new String(new char[100]).replace('\0', 'x');
        String cut = StatusVerdict.text(long100);
        assert cut.length() == StatusVerdict.TEXT_MAX : cut.length();
        assert cut.endsWith("...");

        System.out.println("StatusVerdictTest ok");
    }
}
