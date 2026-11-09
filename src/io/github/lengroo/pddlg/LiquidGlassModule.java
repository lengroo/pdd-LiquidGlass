package io.github.lengroo.pddlg;

import android.app.Activity;
import android.app.Instrumentation;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.lang.reflect.Method;

public class LiquidGlassModule extends XposedModule {

    static final String TAG = "LiquidGlass";

    /** The app this process belongs to; null until onModuleLoaded resolves it. */
    private static volatile HostApp sApp;

    private static volatile int sResumeHits;
    private static volatile LiquidGlassModule sSelf;

    public LiquidGlassModule() {
        super();
        sSelf = this;
    }

    static HostApp app() {
        return sApp;
    }

    /** Hooks an executable, running fn AFTER the original and ignoring its result. */
    static void hookAfter(java.lang.reflect.Executable ex, AfterCallback fn) {
        LiquidGlassModule self = sSelf;
        if (self == null) {
            throw new IllegalStateException("module instance not attached yet");
        }
        self.hook(ex)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        fn.after(chain);
                    } catch (Throwable t) {
                        logErr("after-hook failed", t);
                    }
                    return result;
                });
    }

    /**
     * Hooks an executable, handing the callback the whole call chain so it can
     * rewrite arguments, substitute a result, or drop the call outright.
     *
     * <p>PROTECTIVE like {@link #hookAfter}: a throw out of the callback must
     * never surface in the host's own frame. The chain here wraps app calls
     * that legitimately throw — ViewPager2 rejects setCurrentItem mid
     * fake-drag — and reflection would repackage that as an
     * InvocationTargetException the app has no catch for.
     */
    static void hookIntercept(java.lang.reflect.Executable ex, InterceptCallback fn) {
        LiquidGlassModule self = sSelf;
        if (self == null) {
            throw new IllegalStateException("module instance not attached yet");
        }
        self.hook(ex)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(fn::intercept);
    }

    interface InterceptCallback {
        Object intercept(XposedInterface.Chain chain) throws Throwable;
    }

    interface AfterCallback {
        void after(XposedInterface.Chain chain) throws Throwable;
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        String proc = param.getProcessName();
        log(android.util.Log.INFO, "onModuleLoaded process=" + proc
                + " api=" + getApiVersion()
                + " framework=" + getFrameworkName() + " " + getFrameworkVersion());
        // Both targets are heavily multi-process (:push, :tools, :MSF,
        // :appbrandX, ...). The home screen lives in the main process only,
        // whose name is the package name; everything else detaches.
        HostApp app = HostApp.forProcess(proc);
        if (app == null) {
            log(android.util.Log.INFO, "not a main process we dress up, detach");
            detach();
            return;
        }
        sApp = app;
        installResumeHook(app);
    }

    /**
     * (Re-)registers the callActivityOnResume hook for this generation.
     * Extracted from onModuleLoaded so onHotReloaded can re-register it
     * after a reload — the framework does NOT replay onModuleLoaded, and
     * the old generation's hook keeps running old code until it is
     * replaced (proven 2026-11-09: old hook reinstalled the green pill
     * after teardown, onResume #2/#3 in the Vector log).
     */
    private void installResumeHook(HostApp app) {
        try {
            Method callOnResume = Instrumentation.class.getMethod(
                    "callActivityOnResume", Activity.class);
            hook(callOnResume)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object arg0 = chain.getArg(0);
                            if (arg0 instanceof Activity) {
                                Activity activity = (Activity) arg0;
                                String name = arg0.getClass().getName();
                                if (app.isLauncherActivity(name)) {
                                    GlassConfig.load(activity);
                                    sResumeHits++;
                                    if (sResumeHits <= 3 || sResumeHits % 20 == 0) {
                                        log(android.util.Log.INFO,
                                                "home activity onResume #" + sResumeHits);
                                    }
                                    LiquidGlassInstaller.scheduleInstall(activity);
                                }
                            }
                        } catch (Throwable t) {
                            logErr("resume hook error", t);
                        }
                        return result;
                    });
            log(android.util.Log.INFO, "hooked Instrumentation.callActivityOnResume for "
                    + app + " (" + app.launcherActivity + ")");
        } catch (Throwable t) {
            logErr("install resume hook failed", t);
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        HostApp app = HostApp.forPackage(param.getPackageName());
        if (app == null || !param.isFirstPackage()) {
            return;
        }
        sApp = app;
        log(android.util.Log.INFO, "target package loaded: " + app
                + " classLoader=" + param.getDefaultClassLoader());
        // The tab bar bridge needs the app's own classes, so it can only be
        // wired once the app class loader exists.
        TabBarBridge.install(app, param.getDefaultClassLoader());
    }

    /**
     * API 102 hot reload, old generation: hand the pill back and let the
     * reload through.
     *
     * <p>The saved state carries plain data only (a Bundle of primitives).
     * The first attempt passed the live Activity itself, and the reload
     * failed in the main process (1 reloaded, 1 failed, pill not green):
     * a live Activity is exactly the kind of object that can break the
     * framework's handoff of that state to the new generation. The new
     * generation finds the live Activity itself via ActivityThread instead.
     * View work must run on the main thread, so an off-main-thread call
     * posts the teardown there and waits for it (bounded: the framework
     * rejects a reload whose main thread stays silent for 15s).
     */
    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        log(android.util.Log.INFO, "hot reload: onHotReloading fired");
        try {
            android.os.Bundle state = new android.os.Bundle();
            state.putBoolean("hadPill", LiquidGlassInstaller.hasLivePill());
            state.putInt("lastIndex", LiquidGlassInstaller.lastSelectedIndex());
            param.setSavedInstanceState(state);
        } catch (Throwable t) {
            logErr("hot reload: could not save state", t);
        }
        Runnable teardown = LiquidGlassInstaller::teardownForHotReload;
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            try {
                teardown.run();
            } catch (Throwable t) {
                logErr("hot reload teardown failed", t);
            }
        } else {
            final java.util.concurrent.CountDownLatch done =
                    new java.util.concurrent.CountDownLatch(1);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    teardown.run();
                } catch (Throwable t) {
                    logErr("hot reload teardown failed", t);
                } finally {
                    done.countDown();
                }
            });
            try {
                done.await(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log(android.util.Log.INFO, "hot reload: old generation torn down");
        return true;
    }

    /**
     * API 102 hot reload, new generation: take over from the old generation.
     *
     * <p>Two facts the 2026-11-09 diagnostic proved (Vector log, reload at
     * 13:18:02): (1) the framework does NOT replay onModuleLoaded /
     * onPackageLoaded, so sApp is null here and the reinstall used to be
     * skipped; (2) the old generation's hooks SURVIVE the reload — its
     * callActivityOnResume hook fired onResume #2/#3 afterwards and
     * reinstalled the OLD (green) pill. So this callback must do the whole
     * takeover itself: unhook every old hook, resolve HostApp directly,
     * re-register this generation's hooks, then reinstall the pill.
     * The tab listener field still holds the old generation's Proxy; the
     * new install handles it via the existing "hook the listener's
     * onTabSelected" branch (isOurs is per-ClassLoader, so the old Proxy
     * reads as the app's own listener and gets hooked for new code).
     */
    @Override
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        try {
            // 1. Unhook every old-generation hook so old code stops running.
            int unhooked = 0;
            try {
                java.util.List<XposedInterface.HookHandle> old =
                        param.getOldHookHandles();
                if (old != null) {
                    for (XposedInterface.HookHandle h : old) {
                        try {
                            h.unhook();
                            unhooked++;
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable t) {
                logErr("hot reload: unhook old hooks failed", t);
            }
            log(android.util.Log.INFO,
                    "hot reload: unhooked " + unhooked + " old hooks");

            // 2. Resolve HostApp directly — no replay to rely on.
            Activity activity = currentResumedActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                log(android.util.Log.INFO, "hot reloaded: no live activity");
                return;
            }
            HostApp app = HostApp.forPackage(activity.getPackageName());
            if (app == null || !app.isLauncherActivity(activity.getClass().getName())) {
                log(android.util.Log.INFO, "hot reloaded: not a launcher activity, pkg="
                        + activity.getPackageName()
                        + " activity=" + activity.getClass().getName());
                return;
            }
            sApp = app;
            log(android.util.Log.INFO, "hot reloaded: sApp=" + app
                    + ", re-registering hooks");

            // Read the selected tab index the old generation saved in
            // onHotReloading. Do NOT set it yet: install() resets
            // sLastIndex to -1 as part of a fresh install, so a restore
            // here is wiped out before the pill exists. It is applied
            // after the install lands, in step 4 below.
            final int restoredIndex;
            {
                int idx = -1;
                try {
                    Object saved = param.getSavedInstanceState();
                    if (saved instanceof android.os.Bundle) {
                        idx = ((android.os.Bundle) saved).getInt("lastIndex", -1);
                    }
                } catch (Throwable ignored) {
                }
                restoredIndex = idx;
            }

            // 3. Re-register this generation's hooks.
            installResumeHook(app);
            try {
                TabBarBridge.install(app, activity.getClass().getClassLoader());
            } catch (Throwable t) {
                logErr("hot reload: TabBarBridge.install failed", t);
            }

            // 4. Reinstall the pill now, then restore the saved tab index
            // after the install. scheduleInstall posts tryInstall to the
            // decor view; posting the restore to the same view queues it
            // after the install's synchronous part (which resets
            // sLastIndex), so the droplet lands on the current tab instead
            // of waiting for the next tab switch.
            log(android.util.Log.INFO, "hot reloaded: reinstalling pill");
            activity.runOnUiThread(() -> {
                try {
                    GlassConfig.load(activity);
                    LiquidGlassInstaller.scheduleInstall(activity);
                    if (restoredIndex >= 0) {
                        activity.getWindow().getDecorView().post(() -> {
                            LiquidGlassInstaller.setLastSelectedIndex(restoredIndex);
                            log(android.util.Log.INFO,
                                    "hot reloaded: restored lastIndex=" + restoredIndex);
                        });
                    } else {
                        log(android.util.Log.INFO,
                                "hot reloaded: no saved lastIndex, droplet waits for next tab switch");
                    }
                } catch (Throwable t) {
                    logErr("hot reloaded reinstall failed", t);
                }
            });
        } catch (Throwable t) {
            logErr("hot reloaded callback failed", t);
        }
    }

    /**
     * The currently resumed Activity in this process, via ActivityThread.
     * Best effort — null means "let the next onResume install". The record
     * fields (mActivities / paused / activity) have been stable across the
     * Android versions this module targets; every step is guarded so a
     * hidden-API change degrades to the onResume fallback instead of
     * breaking the reload.
     */
    private static Activity currentResumedActivity() {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = atClass.getMethod("currentActivityThread").invoke(null);
            java.lang.reflect.Field f = atClass.getDeclaredField("mActivities");
            f.setAccessible(true);
            Object map = f.get(at);
            if (!(map instanceof java.util.Map)) {
                return null;
            }
            for (Object record : ((java.util.Map<?, ?>) map).values()) {
                try {
                    Class<?> rc = record.getClass();
                    java.lang.reflect.Field pausedF = rc.getDeclaredField("paused");
                    pausedF.setAccessible(true);
                    if (pausedF.getBoolean(record)) {
                        continue;
                    }
                    java.lang.reflect.Field actF = rc.getDeclaredField("activity");
                    actF.setAccessible(true);
                    Object act = actF.get(record);
                    if (act instanceof Activity) {
                        return (Activity) act;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            logErr("could not find resumed activity", t);
        }
        return null;
    }

    /**
     * Logs through the framework channel, not logcat: LSPosed's exported
     * module log is what actually reaches a bug report, and plain
     * {@code android.util.Log} lines never land in it (the first PDD field
     * test proved that the hard way — its install trail was invisible).
     */
    static void log(int prio, String msg) {
        LiquidGlassModule self = sSelf;
        if (self != null) {
            try {
                self.log(prio, TAG, msg);
                return;
            } catch (Throwable ignored) {
            }
        }
        android.util.Log.println(prio, TAG, msg);
    }

    static void logErr(String msg, Throwable t) {
        LiquidGlassModule self = sSelf;
        if (self != null) {
            try {
                self.log(android.util.Log.ERROR, TAG, msg, t);
                return;
            } catch (Throwable ignored) {
            }
        }
        android.util.Log.e(TAG, msg, t);
    }
}
