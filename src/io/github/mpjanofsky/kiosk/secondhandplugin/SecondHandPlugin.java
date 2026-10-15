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
        if (!"pair".equals(command) && !"reconnect".equals(command) && !"forget".equals(command)) {
            throw new IllegalArgumentException("Unknown command: " + command);
        }
        run(new Runnable() {
            @Override public void run() {
                try {
                    if ("pair".equals(command)) {
                        String address = service.pair();
                        if (address != null) {
                            Map<String, Object> next = new HashMap<String, Object>(applied);
                            next.put("address", address);
                            applied = next;             // before saving, so the configure() that follows is not a change
                            service.saveAddress(address);
                            service.start(next);
                        }
                    } else if ("forget".equals(command)) {
                        boolean ok = service.forget();
                        host.status(ok ? "The clock is unpaired." : "Could not unpair the clock.", !ok);
                        Map<String, Object> next = new HashMap<String, Object>(applied);
                        next.put("address", "");
                        applied = next;
                        service.saveAddress("");
                        service.start(next);
                    } else {
                        service.reconnect();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    host.log(command + " failed: " + e);
                    host.status("The " + command + " action failed. See the log.", true);
                }
            }
        });
    }

    private void run(Runnable task) {
        try {
            worker.execute(task);
        } catch (RuntimeException e) {
            // stopped
        }
    }

    @Override public void onEvent(String event, Map<String, Object> payload) {}

    @Override public void stop() {
        if (worker != null) {
            worker.shutdownNow();
        }
        if (service != null) {
            service.stop();
        }
    }
}
