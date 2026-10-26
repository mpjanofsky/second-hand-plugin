// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Second Hand: wires Kiosk Satellite to {@link ClockService}. Callbacks only hand work to one plugin
 * thread and return (they must finish within 3 s); the Bluetooth work never runs inside a callback.
 */
public final class SecondHandPlugin implements KioskPlugin {
    /** The longest Reconnect waits for its connect attempt to end; well inside the setup run's 60 s silence limit. */
    static final long RECONNECT_WAIT_MS = 15000;

    private PluginHost host;
    private ClockService service;
    private ExecutorService worker;
    private Map<String, Object> applied = new HashMap<String, Object>();

    @Override public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        this.service = new ClockService(host);
        this.worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "echo-wall-clock-worker");
                t.setDaemon(true);
                return t;
            }
        });
        host.status("Starting.", false);
        apply(settings);
    }

    @Override public void configure(Map<String, Object> settings) {
        // Saving the address after pairing comes back here; only restart when something that matters changed.
        if (!same(settings, applied)) {
            apply(settings);
        } else if (ClockService.shownSetting(settings) != ClockService.shownSetting(applied)) {
            // The tile follows its own setting without restarting the link.
            final boolean shown = ClockService.shownSetting(settings);
            applied = new HashMap<String, Object>(settings);
            run(new Runnable() {
                @Override public void run() {
                    service.showTile(shown);
                }
            });
        }
    }

    private void apply(Map<String, Object> settings) {
        final Map<String, Object> copy = new HashMap<String, Object>(settings);
        applied = copy;
        run(new Runnable() {
            @Override public void run() {
                service.start(copy);
            }
        });
    }

    private static boolean same(Map<String, Object> a, Map<String, Object> b) {
        return String.valueOf(a.get("address")).equals(String.valueOf(b.get("address")));
    }

    @Override public void execute(final String command, Map<String, Object> arguments) {
        if (!isCommand(command)) {
            throw new IllegalArgumentException("Unknown command: " + command);
        }
        run(new Runnable() {
            @Override public void run() {
                perform(command, null);
            }
        });
    }

    private static boolean isCommand(String command) {
        return "pair".equals(command) || "reconnect".equals(command) || "forget".equals(command);
    }

    /** The settings page's Pair, Reconnect and Forget buttons: the same actions as the commands, with progress under the row. */
    private void onButton(Map<String, Object> payload) {
        final Object id = payload.get("id");
        final Object run = payload.get("run");
        if (!(id instanceof String) || !isCommand((String) id) || !(run instanceof String)) {
            return;
        }
        run(new Runnable() {
            @Override public void run() {
                perform((String) id, (String) run);
            }
        });
    }

    /** Runs one action on the worker. {@code setupRun} is the settings button's run, or null for a command. */
    private void perform(String command, String setupRun) {
        SetupReporter reporter = new SetupReporter(host, setupRun);
        service.reporter(reporter);
        try {
            if ("pair".equals(command)) {
                String address = service.pair();
                if (address != null) {
                    Map<String, Object> next = new HashMap<String, Object>(applied);
                    next.put("address", address);
                    applied = next;             // before saving, so the configure() that follows is not a change
                    service.saveAddress(address);
                    service.start(next);
                    reporter.finish(true, "Paired. The plugin is connecting to the clock.");
                } else {
                    reporter.finish(false, "Pairing did not finish.");
                }
            } else if ("forget".equals(command)) {
                boolean ok = service.forget();
                host.status(ok ? "The clock is unpaired." : "Could not unpair the clock.", !ok);
                Map<String, Object> next = new HashMap<String, Object>(applied);
                next.put("address", "");
                applied = next;
                service.saveAddress("");
                service.start(next);
                reporter.finish(ok, ok ? "The clock is unpaired." : "Could not unpair the clock.");
            } else {
                reporter.progress("Connecting to the clock...");
                boolean up = service.reconnectAndWait(RECONNECT_WAIT_MS);
                reporter.finish(up, up ? "Connected to the clock." : "Could not connect to the clock.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            host.log(command + " failed: " + e);
            host.status("The " + command + " action failed. See the log.", true);
            reporter.finish(false, "The " + command + " action failed. See the log.");
        } finally {
            service.reporter(null);
            reporter.close();
        }
    }

    private void run(Runnable task) {
        try {
            worker.execute(task);
        } catch (RuntimeException e) {
            // stopped
        }
    }

    @Override public void onEvent(String event, Map<String, Object> payload) {
        if ("settings.button".equals(event)) {
            onButton(payload);
        }
        // settings.cancel needs no answer: setupResult tells a finished run so, and pairing ends by itself.
    }

    @Override public void stop() {
        if (worker != null) {
            worker.shutdownNow();
        }
        if (service != null) {
            service.stop();
        }
    }
}
