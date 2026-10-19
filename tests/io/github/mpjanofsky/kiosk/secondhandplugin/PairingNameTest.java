// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin;

/** The clock is found by name: the Echo Wall Clock and the Mickey Mouse Edition, nothing else. */
public final class PairingNameTest {
    public static void main(String[] args) {
        assert Pairing.isClockName("EchoWallClk10U");
        assert Pairing.isClockName("EWCMickeyMouse05B");
        assert !Pairing.isClockName("JBL Flip 5");
        assert !Pairing.isClockName("");
        assert !Pairing.isClockName(null);
        System.out.println("PairingNameTest ok");
    }
}
