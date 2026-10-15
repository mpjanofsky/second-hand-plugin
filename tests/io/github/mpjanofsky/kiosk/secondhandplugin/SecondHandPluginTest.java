// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin;

import kiosk.kit.FakeHost;
import java.util.HashMap;
import java.util.Map;

/**
 * The plugin starts and stops against a fake host without blocking a callback, rejects unknown commands, and leaves no
 * thread behind. (Off a device there is no Android, so the service reports that instead of connecting.)
 */
public final class SecondHandPluginTest {
    public static void main(String[] args) throws Exception {
        FakeHost host = new FakeHost();
        SecondHandPlugin plugin = new SecondHandPlugin();
        Map<String, Object> settings = new HashMap<String, Object>();
        settings.put("address", "");
        long t0 = System.currentTimeMillis();
        plugin.start(host, settings);
        assert System.currentTimeMillis() - t0 < 500 : "start returns at once";
        long end = System.currentTimeMillis() + 3000;
        while ("Starting.".equals(host.status) && System.currentTimeMillis() < end) {
            Thread.sleep(10);
        }
        assert !"Starting.".equals(host.status) : "the worker reports a status: " + host.statuses;
        plugin.configure(settings); // unchanged settings: no restart, no crash
        boolean rejected = false;
        try {
            plugin.execute("nope", new HashMap<String, Object>());
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        assert rejected;
        t0 = System.currentTimeMillis();
        plugin.stop();
        assert System.currentTimeMillis() - t0 < 2000 : "stop returns in time";
        Thread.sleep(100);
        for (Thread th : Thread.getAllStackTraces().keySet()) {
            assert !th.getName().startsWith("echo-wall-clock") || !th.isAlive() : "thread left: " + th.getName();
        }
        System.out.println("SecondHandPluginTest ok");
    }
}
