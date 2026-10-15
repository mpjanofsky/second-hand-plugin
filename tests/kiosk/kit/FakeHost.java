// SPDX-License-Identifier: Apache-2.0
package kiosk.kit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * A PluginHost for tests: records what the plugin asked the host to do, so tests check behavior without a
 * kiosk. Shared through the Kiosk Plugin Kit; add a recorder here (and give it back) when a plugin needs
 * another host call. Calls it doesn't record keep the SDK's default (they throw), so a test notices them.
 */
public class FakeHost implements PluginHost {
    public String status;
    public boolean statusError;
    public final List<String> statuses = new ArrayList<>();
    public final List<String> logs = new ArrayList<>();
    public final Set<String> subscriptions = new LinkedHashSet<>();
    public String windowTitle, windowMessage;
    public final Map<String, OverlaySpec> overlays = new java.util.LinkedHashMap<>();
    public final List<String> overlayCalls = new ArrayList<>();
    public final List<String> commands = new ArrayList<>();
    public Map<String, Object> saved = Collections.emptyMap();

    @Override public void status(String message, boolean error) {
        status = message;
        statusError = error;
        statuses.add((error ? "ERROR " : "") + message);
    }

    @Override public void log(String message) { logs.add(message); }
    @Override public void subscribe(String event) { subscriptions.add(event); }
    @Override public void unsubscribe(String event) { subscriptions.remove(event); }
    @Override public void saveSettings(Map<String, Object> values) { saved = values; }

    @Override public void showWindow(String title, String message, String buttonLabel) {
        windowTitle = title;
        windowMessage = message;
    }

    @Override public void hideWindow() { windowTitle = windowMessage = null; }

    @Override public void showOverlay(String key, OverlaySpec spec, OverlayFactory factory) {
        overlays.put(key, spec);
        overlayCalls.add("show " + key);
    }

    @Override public void hideOverlay(String key) {
        overlays.remove(key);
        overlayCalls.add("hide " + key);
    }

    /** Records the command; the test answers by calling the callback itself, or ignores it. */
    @Override public void executeCommand(String command, Map<String, Object> arguments, CommandCallback callback) {
        commands.add(command + " " + arguments);
    }
}
