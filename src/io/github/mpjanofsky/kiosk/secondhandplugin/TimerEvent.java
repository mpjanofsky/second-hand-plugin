// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin;

/** One timer broadcast from Kiosk Satellite (VS_TIMER_*), reduced to what the clock rules need. */
public final class TimerEvent {
    public enum Type { STARTED, UPDATED, CANCELLED, FINISHED, DISMISSED }

    public final Type type;
    public final String timerId;
    public final int secondsLeft;
    public final boolean active;

    /** Prefix of Kiosk Satellite's timer broadcasts; the suffix is the {@link Type} name. */
    public static final String ACTION_PREFIX = "me.jxl.kiosk_satellite.event.VS_TIMER_"; // ks-rules: allow sdk-only documented Android broadcast (android-broadcasts.md), a string not a KS class

    /**
     * Reads a broadcast's action and extras, as they come out of the intent bundle. Kiosk Satellite sends numbers as
     * numbers and booleans as booleans, but a number or flag that arrives as text is read too, so a started timer is
     * never dropped over a type. Returns null for an action that is not a known timer event, or a missing id.
     */
    public static TimerEvent fromBroadcast(String action, Object timerId, Object secondsLeft, Object active) {
        if (action == null || !action.startsWith(ACTION_PREFIX) || timerId == null) {
            return null;
        }
        String id = String.valueOf(timerId);
        if (id.isEmpty()) {
            return null;
        }
        Type type;
        try {
            type = Type.valueOf(action.substring(ACTION_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
        long left = 0;
        if (secondsLeft instanceof Number) {
            left = ((Number) secondsLeft).longValue();
        } else if (secondsLeft != null) {
            try {
                left = (long) Double.parseDouble(String.valueOf(secondsLeft).trim());
            } catch (NumberFormatException e) {
                left = 0;
            }
        }
        boolean isActive = active instanceof Boolean ? (Boolean) active
                : active != null && "true".equalsIgnoreCase(String.valueOf(active).trim());
        return new TimerEvent(type, id, (int) Math.max(0, Math.min(left, 24L * 3600)), isActive);
    }

    public TimerEvent(Type type, String timerId, int secondsLeft, boolean active) {
        this.type = type;
        this.timerId = timerId;
        this.secondsLeft = secondsLeft;
        this.active = active;
    }
}
