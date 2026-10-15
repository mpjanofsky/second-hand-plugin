// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin.protocol;

import java.util.List;

/**
 * The messages a controller (what a real Echo is, to the clock) sends, as the protobuf payload that goes
 * inside a frame. They must be byte-identical to a real Echo's: no messageId, no empty payload field. The
 * clock silently ignores anything bigger.
 */
public final class Messages {
    public static final String ALERT_TIMER = "TIMER";

    private Messages() {}

    private static byte[] header(String namespace, String name) {
        return Proto.concat(Proto.field(1, namespace), Proto.field(2, name));
    }

    private static byte[] directive(String namespace, String name, byte[] payload) {
        byte[] inner = Proto.field(1, header(namespace, name));
        if (payload.length > 0) {
            inner = Proto.concat(inner, Proto.field(2, payload));
        }
        return Proto.field(1, inner);
    }

    public static byte[] discover() {
        return directive("Alexa.Discovery", "Discover", new byte[0]);
    }

    public static byte[] clearIndicator() {
        return directive("Notifications", "ClearIndicator", new byte[0]);
    }

    /** StateUpdate with one state, e.g. {@code timeinfo} or {@code timers=active}. */
    public static byte[] stateUpdate(String state, String value) {
        byte[] pair = Proto.concat(Proto.field(1, state), Proto.field(2, value));
        return directive("Alexa.Gadget.StateListener", "StateUpdate", Proto.field(1, pair));
    }

    /** {@code time} is local time with offset as {@link ClockTime#format} writes it; the clock shows the digits as written. */
    public static byte[] timeInfo(String time) {
        return stateUpdate("timeinfo", time);
    }

    public static byte[] timersActive() {
        return stateUpdate("timers", "active");
    }

    public static byte[] timersCleared() {
        return stateUpdate("timers", "cleared");
    }

    public static byte[] setTimer(String token, String endTime) {
        return directive("Alerts", "SetAlert",
                Proto.concat(Proto.field(1, token), Proto.field(2, ALERT_TIMER), Proto.field(3, endTime)));
    }

    public static byte[] deleteAlert(String token) {
        return directive("Alerts", "DeleteAlert", Proto.field(1, token));
    }

    /** Namespace and name of a message from the clock. */
    public static final class Incoming {
        public final String namespace;
        public final String name;

        Incoming(String namespace, String name) {
            this.namespace = namespace;
            this.name = name;
        }

        public boolean is(String namespace, String name) {
            return this.namespace.equals(namespace) && this.name.equals(name);
        }
    }

    /** Reads a frame payload from the clock. Returns null when it is not a message with a header. */
    public static Incoming parse(byte[] payload) {
        try {
            byte[] event = Proto.firstBytes(Proto.parse(payload), 1);
            byte[] header = event == null ? null : Proto.firstBytes(Proto.parse(event), 1);
            if (header == null) {
                return null;
            }
            List<Proto.Field> fields = Proto.parse(header);
            byte[] ns = Proto.firstBytes(fields, 1);
            byte[] name = Proto.firstBytes(fields, 2);
            if (ns == null || name == null) {
                return null;
            }
            return new Incoming(new String(ns, Proto.UTF8), new String(name, Proto.UTF8));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean isDiscoverResponse(byte[] payload) {
        Incoming in = parse(payload);
        return in != null && in.is("Alexa.Discovery", "Discover.Response");
    }
}
