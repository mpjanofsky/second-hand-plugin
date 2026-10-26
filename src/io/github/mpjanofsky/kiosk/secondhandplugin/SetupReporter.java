// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin;

import me.jxl.kiosk.plugins.PluginHost;

/**
 * Reports one settings-button run to Kiosk Satellite, which shows it under the button on the kiosk and in Remote Admin.
 * With no run (the action came from a gesture or the drawer) it does nothing. A run that hears nothing for 60 s fails,
 * and pairing can wait longer than that for a tap, so a heartbeat repeats the last message every 20 s until the run ends.
 */
final class SetupReporter {
    private static final long HEARTBEAT_MS = 20000;

    private final PluginHost host;
    private final String run;
    private String last = "Working";
    private boolean done;
    private Thread heartbeat;

    SetupReporter(PluginHost host, String run) {
        this.host = host;
        this.run = run;
        if (run != null) {
            heartbeat = new Thread(new Runnable() {
                @Override public void run() {
                    beat();
                }
            }, "echo-wall-clock-run");
            heartbeat.setDaemon(true);
            heartbeat.start();
        }
    }

    /** Progress while the action works. */
    synchronized void progress(String message) {
        if (run == null || done) {
            return;
        }
        last = message;
        send("running", message);
    }

    /** The end of the run: success or error, shown under the row. */
    synchronized void finish(boolean success, String message) {
        if (run == null || done) {
            return;
        }
        done = true;
        send(success ? "success" : "error", message);
        notifyAll();
    }

    /** Stops the heartbeat; a run left open (interrupted) just times out on the host. */
    void close() {
        synchronized (this) {
            done = true;
            notifyAll();
        }
        if (heartbeat != null) {
            heartbeat.interrupt();
        }
    }

    private synchronized void beat() {
        try {
            while (!done) {
                wait(HEARTBEAT_MS);
                if (!done) {
                    send("running", last);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void send(String state, String message) {
        try {
            if (!host.setupResult(run, state, message)) {
                done = true; // canceled, replaced or timed out: nothing more to say
            }
        } catch (UnsupportedOperationException | LinkageError olderApp) {
            done = true; // Kiosk Satellite before 2026.10.30 has no settings buttons, so there is no run to report
        } catch (RuntimeException e) {
            done = true; // the host is revoked once the plugin has stopped
        }
    }
}
