// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kiosk.kit.FakeHost;

/**
 * The settings-page buttons answer on their run: Forget and Reconnect end with a result under the row, a press for an
 * unknown button or without a run is ignored, and a host that has no setup controls does not break the plugin.
 * (Off a device there is no Bluetooth, so Forget reports that it could not unpair; pairing itself is checked on the Echo.)
 */
public final class SetupButtonsTest {
    static final class RunHost extends FakeHost {
        final List<String> results = new ArrayList<String>();
        final Map<String, Object> updated = new HashMap<String, Object>();
        boolean supported = true;

        @Override public boolean setupResult(String run, String state, String message) {
            if (!supported) {
                throw new UnsupportedOperationException("Setup controls are unavailable");
            }
            results.add(run + " " + state + " " + message);
            return true;
        }

        @Override public void updateSettings(Map<String, Object> values) {
            updated.putAll(values);
        }
    }

    static Map<String, Object> press(String id, Object run) {
        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("id", id);
        payload.put("run", run);
        return payload;
    }

    static void waitFor(RunHost host, int count) throws InterruptedException {
        long end = System.currentTimeMillis() + 3000;
        while (host.results.size() < count && System.currentTimeMillis() < end) {
            Thread.sleep(10);
        }
    }

    public static void main(String[] args) throws Exception {
        RunHost host = new RunHost();
        SecondHandPlugin plugin = new SecondHandPlugin();
        Map<String, Object> settings = new HashMap<String, Object>();
        settings.put("address", "");
        plugin.start(host, settings);

        // Off a device there is no link, so Reconnect reports at once that the clock is not reachable.
        plugin.onEvent("settings.button", press("reconnect", "r1"));
        waitFor(host, 2);
        assert host.results.size() == 2 && host.results.get(0).equals("r1 running Connecting to the clock...")
                && host.results.get(1).equals("r1 error Could not connect to the clock.") : host.results;

        plugin.onEvent("settings.button", press("forget", "r2"));
        waitFor(host, 3);
        assert host.results.size() == 3 && host.results.get(2).startsWith("r2 error ") : host.results;
        assert "".equals(host.updated.get("address")) : "forget clears only the address: " + host.updated;

        plugin.onEvent("settings.button", press("nope", "r3"));
        plugin.onEvent("settings.button", press("reconnect", null));
        plugin.onEvent("settings.cancel", press("reconnect", "r1"));
        Thread.sleep(200);
        assert host.results.size() == 3 : "ignored presses report nothing: " + host.results;

        host.supported = false;
        plugin.onEvent("settings.button", press("reconnect", "r4"));
        Thread.sleep(300); // no setup controls on this host: the action still runs and nothing throws
        assert host.results.size() == 3;

        plugin.stop();
        Thread.sleep(100);
        for (Thread th : Thread.getAllStackTraces().keySet()) {
            assert !th.getName().startsWith("echo-wall-clock") || !th.isAlive() : "thread left: " + th.getName();
        }
        System.out.println("SetupButtonsTest ok");
    }
}
