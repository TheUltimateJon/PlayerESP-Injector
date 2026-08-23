package playeresp.inject;

import net.minecraft.client.Minecraft;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Bootstrap {
    private static volatile PlayerEspController controller;
    private static final AtomicBoolean fallbackTaskPending = new AtomicBoolean();
    private static volatile boolean fallbackStarted;

    private Bootstrap() { }

    public static synchronized void start() {
        if (controller != null) return;
        final Minecraft mc = Minecraft.getMinecraft();
        try {
            mc.addScheduledTask(new Runnable() {
                @Override public void run() {
                    if (controller == null) controller = new PlayerEspController();
                }
            }).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(exception);
        } catch (ExecutionException exception) {
            throw new RuntimeException(exception.getCause());
        } catch (TimeoutException exception) {
            throw new IllegalStateException("PlayerESP initialization timed out.", exception);
        }
        if (controller == null) throw new IllegalStateException("PlayerESP controller was not initialized.");
    }

    public static void onClientTick() {
        PlayerEspController value = controller;
        if (value != null) value.onTick();
    }

    public static void onWorldRender(int pass) {
        PlayerEspController value = controller;
        if (value != null) value.onRender(pass);
    }

    public static synchronized void startFallback() {
        start();
        if (fallbackStarted) return;
        final Minecraft mc = Minecraft.getMinecraft();
        try {
            mc.addScheduledTask(new Runnable() {
                @Override public void run() {
                    controller.enableFallbackRendering();
                    controller.onFallbackTick();
                }
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new RuntimeException("PlayerESP fallback initialization failed.", exception);
        }
        fallbackStarted = true;
        Thread scheduler = new Thread(new Runnable() {
            @Override public void run() {
                while (fallbackStarted) {
                    if (fallbackTaskPending.compareAndSet(false, true)) {
                        try {
                            mc.addScheduledTask(new Runnable() {
                                @Override public void run() {
                                    try {
                                        PlayerEspController value = controller;
                                        if (value != null) value.onFallbackTick();
                                    } finally {
                                        fallbackTaskPending.set(false);
                                    }
                                }
                            });
                        } catch (Throwable ignored) {
                            fallbackTaskPending.set(false);
                        }
                    }
                    try {
                        Thread.sleep(50L);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "PlayerESP Client Scheduler");
        scheduler.setDaemon(true);
        scheduler.start();
    }
}
