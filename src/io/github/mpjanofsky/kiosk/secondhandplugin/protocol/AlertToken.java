// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin.protocol;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The clock identifies a timer by a decimal string token (a real Echo sends 8 to 10 digits). Ours is 10 digits
 * derived from the kiosk's timer id, so the same timer always gets the same token: a resume or a reconnect
 * resend replaces the alert instead of adding a second one.
 */
public final class AlertToken {
    private static final BigInteger SPAN = BigInteger.valueOf(9000000000L);
    private static final BigInteger FIRST = BigInteger.valueOf(1000000000L);

    private AlertToken() {}

    public static String fromTimerId(String timerId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(timerId.getBytes(Proto.UTF8));
            byte[] head = new byte[8];
            System.arraycopy(hash, 0, head, 0, 8);
            return new BigInteger(1, head).mod(SPAN).add(FIRST).toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
