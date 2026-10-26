// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kiosk.kit.FakeHost;

/**
 * The Overview tile follows the "Show status tile" setting: on by default, removed when switched off, shown again when
 * switched on, and never touched by a change to some other setting. Off a device there is no Android context, so the
 * tile's first verdict is the "could not reach Android" one.
 */
public final class StatusTileTest {
    static final class TileHost extends FakeHost {
        final List<String> calls = new ArrayList<String>();

        @Override public void publishStatusTile(String key, String title, String level, String text) {
            calls.add("publish " + key + " " + level + " " + text);
        }

        @Override public void removeStatusTile(String key) {
            calls.add("remove " + key);
        }
    }

    static Map<String, Object> settings(boolean tile) {
        Map<String, Object> s = new HashMap<String, Object>();
        s.put("address", "");
        s.put("status_tile", tile);
        return s;
    }

    static void waitFor(TileHost host, int count) throws InterruptedException {
        long end = System.currentTimeMillis() + 3000;
        while (host.calls.size() < count && System.currentTimeMillis() < end) {
            Thread.sleep(10);
        }
    }

    public static void main(String[] args) throws Exception {
        TileHost host = new TileHost();
        SecondHandPlugin plugin = new SecondHandPlugin();
        plugin.start(host, settings(true));
        waitFor(host, 1);
        assert host.calls.size() == 1 : host.calls;
        assert host.calls.get(0).startsWith("publish " + StatusVerdict.KEY + " off ") : host.calls.get(0);

        plugin.configure(settings(false));
        waitFor(host, 2);
        assert host.calls.get(1).equals("remove " + StatusVerdict.KEY) : host.calls;

        plugin.configure(settings(true));
        waitFor(host, 3);
        assert host.calls.get(2).startsWith("publish " + StatusVerdict.KEY + " off ") : host.calls;

        // Saving the same settings again changes nothing on the tile.
        plugin.configure(settings(true));
        Thread.sleep(100);
        assert host.calls.size() == 3 : host.calls;

        plugin.stop();
        System.out.println("StatusTileTest ok");
    }
}
