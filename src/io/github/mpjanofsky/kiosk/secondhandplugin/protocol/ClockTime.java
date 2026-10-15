// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin.protocol;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Times as the clock wants them: local time with the UTC offset, {@code 2026-10-14T12:00:00-04:00}, always
 * 25 characters. The clock shows the digits as written and ignores the offset, so the local part must be right.
 * Built with Calendar because java.time needs Android 8.
 */
public final class ClockTime {
    private ClockTime() {}

    public static String format(long epochMillis, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone, Locale.ROOT);
        c.setTimeInMillis(epochMillis);
        int offsetMinutes = zone.getOffset(epochMillis) / 60000;
        char sign = offsetMinutes < 0 ? '-' : '+';
        int abs = Math.abs(offsetMinutes);
        return String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02d%c%02d:%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND),
                sign, abs / 60, abs % 60);
    }
}
