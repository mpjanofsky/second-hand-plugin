// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin;

/**
 * What the Remote Admin Overview tile says for each link state. Plain Java, so the mapping is tested off-device.
 * The text is the status line itself: it never carries the address, and it is cut to the host's 80-character limit.
 */
final class StatusVerdict {
    static final String KEY = "second_hand";
    static final String TITLE = "Second Hand";
    static final int TEXT_MAX = 80;

    private StatusVerdict() {
    }

    /** "on" connected, "warn" retrying, "off" unavailable (Bluetooth off), "" otherwise, and "" with no clock paired. */
    static String level(ClockLink.State state, boolean paired) {
        if (!paired) {
            return "";
        }
        switch (state) {
            case CONNECTED:
                return "on";
            case RETRYING:
                return "warn";
            case UNAVAILABLE:
                return "off";
            default:
                return "";
        }
    }

    /** The status line as tile text: control characters become spaces, long text ends in "...". */
    static String text(String statusLine) {
        String clean = statusLine == null ? "" : statusLine.replaceAll("\\p{Cntrl}", " ").trim();
        if (clean.isEmpty()) {
            return TITLE;
        }
        if (clean.length() > TEXT_MAX) {
            return clean.substring(0, TEXT_MAX - 3) + "...";
        }
        return clean;
    }
}
