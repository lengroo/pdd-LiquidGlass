package io.github.lengroo.pddlg;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/**
 * Moves the host app's bottom tab bar into a floating liquid-glass pill.
 *
 * <p>Unlike the HeyBox original this is ported from, no layout surgery on the
 * content area is needed. Both supported apps already lay the content out at
 * full screen height with the tab bar floating over it inside a FrameLayout —
 * WeChat's {@code CustomViewPager}, QQ's {@code tabcontent} — so the backdrop
 * the glass refracts is there from the start. We only reparent the bar and
 * strip the solid colour the app paints behind it.
 *
 * <p>Nothing below names a class: the bar arrives already located (see
 * {@link TabBarBridge}) and everything from there on is geometry.
 */
final class LiquidGlassInstaller {

    /**
     * Retry budget for the first layout pass after the home activity resumes.
     * Both apps build their home screen asynchronously (WeChat's
     * FirstScreenFrameLayout and blink preloading; QQ's SplashActivity, which
     * may sit on a login screen first), so the bar can show up seconds after
     * onResume. This polling is only a safety net — the primary trigger is the
     * tab-switch hook.
     */
    private static final int MAX_ATTEMPTS = 40;
    private static final long RETRY_DELAY_MS = 250L;

    /** Scratch for on-screen positions; every use is on the UI thread. */
    private static final int[] sLoc = new int[2];
    /** Scratch for host-relative positions; every use is on the UI thread. */
    private static final int[] sPos = new int[2];
    private static boolean sKeepFailed;
    private static WeakReference<Activity> sActivityRef = new WeakReference<>(null);
    private static WeakReference<LiquidGlassHostLayout> sHostRef = new WeakReference<>(null);
    private static WeakReference<View> sDropletRef = new WeakReference<>(null);
    private static WeakReference<ViewGroup> sTabRowRef = new WeakReference<>(null);
    private static WeakReference<ViewGroup> sPagerRef = new WeakReference<>(null);
    private static int sLastIndex = -1;
    private static WeakReference<View> sTabViewRef = new WeakReference<>(null);
    private static WeakReference<View> sGlassRef = new WeakReference<>(null);
    private static DropletDragController sDrag;
    /** Identity/children fingerprint of the row the renderer is currently bound to. */
    private static int sTabStructureSignature;
    private static boolean sTabStructureRefreshPosted;
    /**
     * Uptime since which the live tab row has been unusable while the old
     * binding is dead too, or 0. Timed rather than counted in frames: with
     * nothing moving no frames come, and the wait would never run out.
     */
    private static long sTabRowEmptySinceMs;
    private static final long TAB_ROW_EMPTY_GIVE_UP_MS = 2000L;
    /** Content height established during install, before QQ's navigation reserve. */
    private static int sBarHeight;
    /**
     * Last real navigation-bar inset seen before our edge-to-edge listener
     * strips it from the app's view tree. Kept across Activity recreation: QQ
     * rebuilds SplashActivity when its skin follows system day/night, while the
     * DecorView (and therefore the stripping listener) can survive long enough
     * for the replacement tab bar to report an inset of zero.
     */
    private static int sNavigationInset;
    /** The app's own frosted strip and the bar's hairline, held hidden. */
    private static WeakReference<View> sBlurLayerRef = new WeakReference<>(null);
    private static WeakReference<View> sHairlineRef = new WeakReference<>(null);
    private static boolean sBlurRelit;
    /** Droplet's resting Y inside the host, before WeChat's bar offset. */
    private static float sDropletBaseY;
    /** Geometry the droplet's size and offset were last computed against. */
    private static int sDropletGeometryKey;
    private static boolean sDropletLogged;

    private LiquidGlassInstaller() {
    }

    /**
     * How long the stock bar stays hidden before it is handed back. Sized to
     * outlast the install polling above, so a slow cold start does not flash
     * the original bar a beat before the pill replaces it.
     */
    private static final long REVEAL_TIMEOUT_MS = 8000L;

    /**
     * Keeps the app's own bar invisible until the pill takes it over.
     *
     * <p>Installing cannot happen before the first layout, and the app has drawn
     * its own bar by then — which reads as a flash of the original on every cold
     * start. Hooking the bar's constructor would be the natural place to catch
     * it, but Tinker means the class our loader resolves is not the one the live
     * view comes from, so the hook never fires. Matching on the class name from
     * a pre-draw listener sidesteps that entirely, and pre-draw is the last
     * point before anything reaches the screen.
     *
     * <p>The timeout hands the bar back if the pill never arrives: a permanently
     * invisible tab bar would be far worse than a flash.
     */
    private static void hideStockBarUntilInstalled(View decor) {
        decor.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    private final long deadline =
                            android.os.SystemClock.uptimeMillis() + REVEAL_TIMEOUT_MS;
                    private View bar;

                    @Override
                    public boolean onPreDraw() {
                        if (bar == null || bar.getParent() == null) {
                            bar = TabBarBridge.findTabView(decor);
                        }
                        boolean installed = bar != null
                                && bar.getParent() instanceof LiquidGlassHostLayout;
                        boolean expired =
                                android.os.SystemClock.uptimeMillis() > deadline;
                        if (installed || expired) {
                            if (bar != null && !installed) {
                                bar.setAlpha(1f);
                                if (bar.getParent() instanceof ViewGroup) {
                                    showOwnBlurLayers((ViewGroup) bar.getParent());
                                }
                                LiquidGlassModule.log(android.util.Log.WARN,
                                        "pill never took over, stock bar restored");
                            }
                            decor.getViewTreeObserver().removeOnPreDrawListener(this);
                        } else if (bar != null && bar.getAlpha() != 0f) {
                            bar.setAlpha(0f);
                            // QQ's own frosted strip is a separate view and
                            // stays lit with the bar faded out, which reads as
                            // a grey band across the bottom for the whole cold
                            // start. It goes at the same moment the bar does.
                            if (bar.getParent() instanceof ViewGroup) {
                                hideOwnBlurLayers((ViewGroup) bar.getParent(), bar);
                            }
                        }
                        return true;
                    }
                });
    }

    static void scheduleInstall(Activity activity) {
        // Kept for extendUnderNavBar: WeChat's views hand out a context that does
        // not wrap the Activity, so the window is not reachable from them.
        sActivityRef = new WeakReference<>(activity);
        View decor = activity.getWindow().getDecorView();
        if (sHostRef.get() == null) {
            hideStockBarUntilInstalled(decor);
        }
        decor.post(() -> tryInstall(activity, decor, 0));
    }

    private static void tryInstall(Activity activity, View decor, int attempt) {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            // Only skip if the pill is live in *this* window. WeChat's process
            // outlives a swipe-away from recents, so these statics still point at
            // the destroyed Activity's host — treating that as "already installed"
            // left the relaunched LauncherUI with its stock bar.
            LiquidGlassHostLayout live = sHostRef.get();
            if (live != null && live.isAttachedToWindow()
                    && live.getRootView() == decor.getRootView()) {
                // Nothing to install, but coming back from a chat or any other
                // screen rebuilds the window state: the navigation inset is
                // applied again and the dead strip at the bottom re-opens.
                reassertBottom(activity);
                return;
            }
            if (live != null) {
                resetState();
            }
            ViewGroup tabView = TabBarBridge.locateTabView(decor);
            if (tabView == null) {
                if (attempt < MAX_ATTEMPTS) {
                    decor.postDelayed(
                            () -> tryInstall(activity, decor, attempt + 1),
                            RETRY_DELAY_MS);
                } else {
                    LiquidGlassModule.log(android.util.Log.WARN,
                            "tab bar not found after " + MAX_ATTEMPTS
                                    + " attempts, giving up; tree="
                                    + TabBarBridge.describeTree(decor));
                }
                return;
            }
            // PDD builds its bar asynchronously: the PddTabView is in the
            // tree a beat before its five tabs are. Installing against the
            // still-empty bar bound the droplet and the drag controller to a
            // null row (the field tests' drag=false, healed later by the
            // structure refresh), so an empty row is a retry, not an install.
            ViewGroup earlyRow = TabBarBridge.findTabRow(tabView);
            if (earlyRow == null || TabBarBridge.tabCount(earlyRow) == 0) {
                if (attempt < MAX_ATTEMPTS) {
                    decor.postDelayed(
                            () -> tryInstall(activity, decor, attempt + 1),
                            RETRY_DELAY_MS);
                } else {
                    LiquidGlassModule.log(android.util.Log.WARN,
                            "tab bar present but its row never populated, giving up");
                }
                return;
            }
            install(tabView);
        } catch (Throwable t) {
            LiquidGlassModule.logErr("install failed", t);
        }
    }

    /**
     * A copy of {@code src} in its own LayoutParams class, so parent-specific
     * anchoring survives the copy.
     *
     * <p>The exact-class copy constructor comes first, and it is the one that
     * matters: {@code RelativeLayout.LayoutParams} only carries its anchoring
     * rules through its own {@code (LayoutParams)} constructor, while the
     * {@code (MarginLayoutParams)} constructor — what 1.0.1 fell back to —
     * copies the margins and silently drops every rule, which is why the pill
     * stayed in the top-left corner on the second field test. Rules and
     * margins are then transferred explicitly as well, so the copy anchors
     * exactly where the original did however it was constructed.
     */
    private static ViewGroup.LayoutParams copyLayoutParams(
            ViewGroup.LayoutParams src) {
        ViewGroup.LayoutParams copy = null;
        try {
            copy = src.getClass()
                    .getConstructor(src.getClass())
                    .newInstance(src);
        } catch (Throwable ignored) {
        }
        if (copy == null) {
            try {
                copy = src.getClass()
                        .getConstructor(ViewGroup.MarginLayoutParams.class)
                        .newInstance(src);
            } catch (Throwable ignored) {
            }
        }
        if (copy == null) {
            try {
                copy = src.getClass()
                        .getConstructor(ViewGroup.LayoutParams.class)
                        .newInstance(src);
            } catch (Throwable ignored) {
            }
        }
        if (copy == null) {
            return null;
        }
        if (src instanceof android.widget.RelativeLayout.LayoutParams
                && copy instanceof android.widget.RelativeLayout.LayoutParams) {
            int[] rules = ((android.widget.RelativeLayout.LayoutParams) src)
                    .getRules();
            android.widget.RelativeLayout.LayoutParams rl =
                    (android.widget.RelativeLayout.LayoutParams) copy;
            for (int verb = 0; verb < rules.length; verb++) {
                rl.addRule(verb, rules[verb]);
            }
        }
        if (src instanceof ViewGroup.MarginLayoutParams
                && copy instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams s = (ViewGroup.MarginLayoutParams) src;
            ((ViewGroup.MarginLayoutParams) copy).setMargins(
                    s.leftMargin, s.topMargin, s.rightMargin, s.bottomMargin);
        }
        return copy;
    }

    /** Drops references to a previous Activity's views so a relaunch reinstalls. */
    private static void resetState() {
        sHostRef = new WeakReference<>(null);
        sTabViewRef = new WeakReference<>(null);
        sGlassRef = new WeakReference<>(null);
        sDropletRef = new WeakReference<>(null);
        sTabRowRef = new WeakReference<>(null);
        sPagerRef = new WeakReference<>(null);
        sDrag = null;
        sTabStructureSignature = 0;
        sTabStructureRefreshPosted = false;
        sTabRowEmptySinceMs = 0L;
        sBarHeight = 0;
        sBlurLayerRef = new WeakReference<>(null);
        sHairlineRef = new WeakReference<>(null);
        sNavBgRef = new WeakReference<>(null);
        sBlurRelit = false;
        sLastIndex = -1;
        sDropletBaseY = 0f;
        sDropletGeometryKey = 0;
        sDropletLogged = false;
        sPaddedScrollers.clear();
        sScrollerPad = 0;
        sPageBarDisagreeSinceMs = 0L;
        LiquidGlassModule.log(android.util.Log.INFO,
                "stale host from a previous Activity dropped, reinstalling");
    }

    /**
     * The pager the glass is currently refracting, or null before the first
     * install. Re-read on every use rather than captured: {@link #resetState}
     * drops it when an Activity is recreated and the next install publishes a
     * fresh instance, so a cached reference would go stale for the rest of the
     * process.
     */
    static ViewGroup currentPager() {
        return sPagerRef.get();
    }

    private static void install(ViewGroup tabView) {
        ViewGroup parent = tabView.getParent() instanceof ViewGroup
                ? (ViewGroup) tabView.getParent() : null;
        if (parent == null) {
            return;
        }
        if (parent instanceof LiquidGlassHostLayout) {
            return; // already installed
        }

        ViewGroup backdrop = findBackdrop(parent, tabView);
        if (backdrop == null) {
            LiquidGlassModule.log(android.util.Log.WARN,
                    "no backdrop sibling found, glass would refract nothing");
            return;
        }

        int index = parent.indexOfChild(tabView);
        if (index < 0) {
            return;
        }
        ViewGroup.LayoutParams originalLp = tabView.getLayoutParams();

        Context ctx = tabView.getContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        int bottomOffset = Math.round(density * GlassConfig.barOffsetDp);

        int navigationInset = rememberNavigationInset(parent);

        LiquidGlassHostLayout host = new LiquidGlassHostLayout(ctx, backdrop, tabView);

        int navReserve = tabView.getPaddingBottom();
        // Read before the drop lands: the bar is still laid out at its old
        // height here, and the reserve is exactly what the pill must not keep.
        ViewGroup tabRow = TabBarBridge.findTabRow(tabView);
        // PDD's row *is* the bar, so the generic height math — which counts
        // the row's offset inside the bar as a top gap — reads the bar's
        // offset inside its parent (2559px) as that gap and always falls back
        // to the full 193px. The field screenshots show the result: a 47px
        // dead band across the top of the pill while the 146px tabs sit at
        // the bottom, and the droplet riding low inside it. PDD gets its own
        // height, measured off the tab content alone.
        int barHeight = LiquidGlassModule.app() == HostApp.PDD
                && tabRow == tabView
                ? pddBarHeight(tabRow, tabView.getHeight() - navReserve, density)
                : contentBarHeight(tabRow, tabView.getHeight() - navReserve);

        // KernelSU's floating bar is width(IntrinsicSize.Min) — it hugs its tabs
        // and floats centred, rather than stretching edge to edge. The width has
        // to be resolved up front and pinned: leaving the host WRAP_CONTENT makes
        // the MATCH_PARENT glass layer measure to zero and vanish.
        // The shadow is drawn inside the host's own padding — setElevation never
        // renders anything in this view tree — so the host is inflated by that
        // much and pulled back down by the same amount.
        host.setupShadow(density, isNight(ctx));
        int shadowPad = host.shadowPad();

        TabGeometrySnapshot geometry = new TabGeometrySnapshot(tabView, tabRow);
        // The host takes the bar's place, so it must take the bar's layout
        // contract too. WeChat/QQ parents are FrameLayouts and the pill is
        // gravity-anchored there. PDD's MainFrameContainerView is a
        // RelativeLayout whose bar carries ALIGN_PARENT_BOTTOM in its own
        // params — handing it FrameLayout params drops the rule, and the
        // first PDD build put the pill in the top-left corner of the screen.
        // For a non-FrameLayout parent the host therefore inherits a copy of
        // the bar's own params, anchoring rules included.
        ViewGroup.MarginLayoutParams hostLp;
        if (parent instanceof FrameLayout || originalLp == null) {
            hostLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        } else {
            ViewGroup.LayoutParams copy = copyLayoutParams(originalLp);
            if (copy instanceof ViewGroup.MarginLayoutParams) {
                hostLp = (ViewGroup.MarginLayoutParams) copy;
                hostLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                hostLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            } else {
                hostLp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
            }
        }
        // The navigation inset only counts once the window reaches under it —
        // either because extendUnderNavBar grew it or because the app already
        // draws edge to edge. Short of that the pill's parent stops above the
        // gesture bar, and adding it again floats the pill far too high. Left
        // out here and applied below, once that is known.
        // Anchored on the parent, not the bar: the bar is about to be detached,
        // and a detached view reports no insets at all. Reading from it later
        // would silently drop the correction and let the bar sink onto the
        // gesture pill.
        hostLp.bottomMargin = bottomOffset - shadowPad;
        LiquidGlassModule.log(android.util.Log.INFO,
                "host lp=" + hostLp.getClass().getSimpleName()
                        + (hostLp instanceof android.widget.RelativeLayout.LayoutParams
                                ? " rules=" + java.util.Arrays.toString(
                                        ((android.widget.RelativeLayout.LayoutParams) hostLp)
                                                .getRules())
                                : "")
                        + " bottomMargin=" + hostLp.bottomMargin);
        FrameLayout.LayoutParams tabLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                barHeight > 0 ? barHeight : ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.FILL_HORIZONTAL);

        // The native bar must never be left detached. All potentially failing
        // setup above runs while it still belongs to the app; this small
        // structural transaction either installs both levels or puts the bar
        // straight back at its original index.
        try {
            parent.removeView(tabView);
            parent.addView(host, index, hostLp);
            host.addView(tabView, tabLp);
        } catch (Throwable t) {
            restoreAfterFailedReparent(parent, tabView, host, index, originalLp);
            LiquidGlassModule.logErr("could not reparent the stock tab bar", t);
            return;
        }

        try {
            dropNavReserve(tabView);
            // The extra pill height has to land evenly above and below the
            // tabs, or the pill just grows a dead band on one side again.
            // Gravity cannot be trusted for this — a bottom-aligned tab
            // column stays bottom-aligned however tall the bar gets — so
            // the bar carries the extra as symmetric padding, leaving
            // exactly one tab height of room. The hug below preserves
            // vertical padding for this reason.
            if (LiquidGlassModule.app() == HostApp.PDD && tabRow != null) {
                int tabH = 0;
                for (int i = 0; i < tabRow.getChildCount(); i++) {
                    View t = tabRow.getChildAt(i);
                    if (t.getVisibility() == View.VISIBLE) {
                        tabH = Math.max(tabH, t.getHeight());
                    }
                }
                int vPad = Math.max(0, (barHeight - tabH) / 2);
                if (vPad > 0) {
                    tabView.setPadding(tabView.getPaddingLeft(), vPad,
                            tabView.getPaddingRight(), vPad);
                    LiquidGlassModule.log(android.util.Log.INFO,
                            "pdd pill vertical padding=" + vPad
                                    + " barH=" + barHeight + " tabH=" + tabH);
                }
            }
            int barWidth = hugContentWidth(tabRow, density);
            if (barWidth > 0) {
                int desired = barWidth + shadowPad * 2;
                if (parent.getWidth() > 0) {
                    desired = Math.min(desired, parent.getWidth());
                }
                hostLp.width = desired;
                host.setLayoutParams(hostLp);
            }
        } catch (Throwable t) {
            try {
                geometry.restore(density);
            } catch (Throwable restoreError) {
                LiquidGlassModule.logErr("could not restore stock tab geometry",
                        restoreError);
            }
            restoreAfterFailedReparent(parent, tabView, host, index, originalLp);
            LiquidGlassModule.logErr("could not size the floating tab bar", t);
            return;
        }

        // Safe to show again: the glass goes in below it in this same pass, so
        // the two appear together.
        tabView.setAlpha(1f);

        sHostRef = new WeakReference<>(host);
        sTabViewRef = new WeakReference<>(tabView);
        sTabRowRef = new WeakReference<>(tabRow);
        sTabStructureSignature = tabStructureSignature(tabRow);
        sTabStructureRefreshPosted = false;
        sPagerRef = new WeakReference<>(backdrop);
        sBarHeight = barHeight;
        sLastIndex = -1;

        TabBarBridge.tryHookPager(backdrop);

        // Cosmetic cleanup cannot invalidate the structural install. If an app
        // skin changes one of these details, keep the functional floating bar
        // and report the degraded effect rather than trying to tear it back out.
        try {
            hideOwnBlurLayers(parent, tabView);
            hideBarHairline(parent, tabView);
            stripSolidBackgrounds(tabView);
            disableTabWidgetStrips(tabView);
            TabBarBridge.tagTabIcons(tabRow);
            centerPddTabContent(tabRow);
        } catch (Throwable t) {
            LiquidGlassModule.logErr("could not remove all stock tab chrome", t);
        }

        boolean insetCounts = extendUnderNavBar(ctx);
        if (insetCounts) {
            hostLp.bottomMargin = bottomOffset - shadowPad + navigationInset;
            host.setLayoutParams(hostLp);
            // The first real insets dispatch can arrive either side of host
            // creation. Re-read the shared cache on the next UI turn so both
            // orders converge on the same bottom anchor.
            host.post(() -> syncHostBottomInset(host, sNavigationInset));
        }

        // Open up clipping all the way to the content root: the droplet grows
        // past the pill while dragging, and any ancestor still clipping its
        // children would shear that overflow off.
        unclipAncestors(parent);
        attachRenderer(ctx, host, backdrop, density, tabRow);
        installSelectionWatcher(host);
        watchBottomInset(host, backdrop);
        // PDD's selection signal lives on its own tab-change listener; the
        // bridge attaches to whatever registration the bar currently holds.
        // A no-op for the other apps.
        try {
            TabBarBridge.attachPddSelectionListener(tabView);
        } catch (Throwable t) {
            LiquidGlassModule.logErr("could not attach the selection listener", t);
        }

        host.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    private boolean done;

                    @Override
                    public void onGlobalLayout() {
                        if (done) {
                            return;
                        }
                        done = true;
                        host.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        host.attach();
                        // Laid-out heights are only guaranteed here: if the
                        // install-time pass ran before the first layout, its
                        // centring measured zeros and skipped, and nothing
                        // ever re-ran it. Translation is idempotent (layout
                        // positions do not move with it), so re-running here
                        // is safe even when install already centred.
                        centerPddTabContent(tabRow);
                        syncDropletSize(TabBarBridge.currentIndex(tabView));
                        extendPagesToBottom(backdrop);
                        // The window grows a beat after the flag is set, and the
                        // pages have to be re-stretched into the room that frees.
                        host.postDelayed(() -> extendPagesToBottom(backdrop), 500L);
                        LiquidGlassModule.log(android.util.Log.INFO,
                                "liquid glass installed: hostW=" + host.getWidth()
                                        + " hostH=" + host.getHeight()
                                        + " barH=" + tabView.getHeight()
                                        + " children=" + host.getChildCount());
                    }
                });
    }

    /** Restores the app-owned bar if the two-level reparent cannot complete. */
    private static void restoreAfterFailedReparent(
            ViewGroup parent, View tabView, LiquidGlassHostLayout host,
            int index, ViewGroup.LayoutParams originalLp) {
        try {
            if (tabView.getParent() instanceof ViewGroup
                    && tabView.getParent() != parent) {
                ((ViewGroup) tabView.getParent()).removeView(tabView);
            }
            if (host.getParent() instanceof ViewGroup) {
                ((ViewGroup) host.getParent()).removeView(host);
            }
            if (tabView.getParent() == null) {
                int safeIndex = Math.max(0, Math.min(index, parent.getChildCount()));
                if (originalLp != null) {
                    parent.addView(tabView, safeIndex, originalLp);
                } else {
                    parent.addView(tabView, safeIndex);
                }
            }
            tabView.setAlpha(1f);
        } catch (Throwable restoreError) {
            LiquidGlassModule.logErr("could not restore the stock tab bar", restoreError);
        }
    }

    /** Reversible geometry touched while turning equal-width tabs into a pill. */
    private static final class TabGeometrySnapshot {
        private final View mTabView;
        private final int[] mTabPadding = new int[4];
        private final ViewGroup mRow;
        private final int[] mRowPadding = new int[4];
        private final View[] mTabs;
        private final int[] mWidths;
        private final float[] mWeights;
        private final boolean[] mHadLayoutParams;

        TabGeometrySnapshot(View tabView, ViewGroup row) {
            mTabView = tabView;
            mTabPadding[0] = tabView.getPaddingLeft();
            mTabPadding[1] = tabView.getPaddingTop();
            mTabPadding[2] = tabView.getPaddingRight();
            mTabPadding[3] = tabView.getPaddingBottom();
            mRow = row;
            int count = row == null ? 0 : row.getChildCount();
            mTabs = new View[count];
            mWidths = new int[count];
            mWeights = new float[count];
            mHadLayoutParams = new boolean[count];
            if (row == null) {
                return;
            }
            mRowPadding[0] = row.getPaddingLeft();
            mRowPadding[1] = row.getPaddingTop();
            mRowPadding[2] = row.getPaddingRight();
            mRowPadding[3] = row.getPaddingBottom();
            for (int i = 0; i < count; i++) {
                View tab = row.getChildAt(i);
                mTabs[i] = tab;
                ViewGroup.LayoutParams lp = tab.getLayoutParams();
                if (lp == null) {
                    continue;
                }
                mHadLayoutParams[i] = true;
                mWidths[i] = lp.width;
                mWeights[i] = lp instanceof android.widget.LinearLayout.LayoutParams
                        ? ((android.widget.LinearLayout.LayoutParams) lp).weight : 0f;
            }
        }

        void restore(float density) {
            mTabView.setPadding(mTabPadding[0], mTabPadding[1],
                    mTabPadding[2], mTabPadding[3]);
            if (mRow == null) {
                return;
            }
            mRow.setPadding(mRowPadding[0], mRowPadding[1],
                    mRowPadding[2], mRowPadding[3]);
            for (int i = 0; i < mTabs.length; i++) {
                if (!mHadLayoutParams[i]) {
                    continue;
                }
                View tab = mTabs[i];
                ViewGroup.LayoutParams lp = tab.getLayoutParams();
                if (lp == null) {
                    continue;
                }
                lp.width = mWidths[i];
                if (lp instanceof android.widget.LinearLayout.LayoutParams) {
                    ((android.widget.LinearLayout.LayoutParams) lp).weight = mWeights[i];
                }
                tab.setLayoutParams(lp);
            }
        }
    }

    private static final int EXTEND_TAG_KEY = 0x7F5A0001;

    /**
     * Lets every page's content run to the bottom of the screen, so the list
     * keeps rendering behind and below the floating pill.
     *
     * <p>WeChat sizes each page to stop where the docked bar used to start. Once
     * the bar floats, that band would otherwise show bare page background. The
     * content is stretched to the full height and the scrolling views get bottom
     * padding, so rows scroll through underneath the pill and the last one can
     * still clear it.
     *
     * <p>WeChat re-applies its own LayoutParams on later layout passes (that is
     * why the Contacts page kept snapping back), so each container also gets a
     * layout listener that re-stretches it whenever that happens.
     */
    private static void extendPagesToBottom(ViewGroup pager) {
        if (pager == null) {
            return;
        }
        if (LiquidGlassModule.app() == HostApp.PDD) {
            // Expand the feed to fill the black strip below the pill.
            // PDD's pages are NOT full height — the backdrop is 2464px on
            // a 2800px parent, so the feed can never reach the strip below
            // the pill no matter how its padding is cleared. Stretch the
            // backdrop, the CustomViewPager and its pages to the parent's
            // full height first; only then can the scroller pass below fill
            // the strip. Clearing the scrollers' bottom padding to 0 lets
            // rows lay out all the way to the parent's bottom, so the strip
            // shows product rows instead of the window background. The last
            // row then sits partly under the pill (visible through the
            // glass) instead of scrolling fully clear. The nav strip below
            // the parent is outside the feed and stays black — only the
            // band inside the parent fills. This runs on install and on
            // every tab change (the watcher posts extendPagesToBottom when
            // sel changes), so new pages get it too.
            stretchPddContentToBottom(pager);
            padScrollersBottom(pager, 0, 0);
            if (!sFeedExpandLogged) {
                sFeedExpandLogged = true;
                LiquidGlassModule.log(android.util.Log.INFO,
                        "pdd feed expand: scrollers padded to 0");
            }
            return;
        }
        // The pager itself first. A page can only be stretched as far as its
        // container reaches, and WeChat sizes the pager to stop where the docked
        // bar used to start. Left short, the band the bar vacated shows the root
        // layout's own background instead of the page — the pale strip that
        // appears under the floating pill. Its parent is the FrameLayout the bar
        // was pulled out of, so this is a plain margin/height fix with no
        // positioning rules to undo.
        ViewGroup pagerParent = pager.getParent() instanceof ViewGroup
                ? (ViewGroup) pager.getParent() : null;
        if (pagerParent != null) {
            stretchToBottom(pager, pagerParent.getHeight());
        }
        int target = pager.getHeight();
        for (int i = 0; i < pager.getChildCount(); i++) {
            View page = pager.getChildAt(i);
            if (page instanceof ViewGroup) {
                // And the page itself, for the same reason: WeChat sizes each
                // page to stop where the docked bar started, and a child can
                // only be stretched as far as the page it sits in. Left as it
                // is, the band the bar vacated shows the DecorView's own
                // background through the pager — the strip under the pill.
                dropBottomFrost(page);
                stretchToBottom(page, target);
                extendOnePage((ViewGroup) page, target);
            }
        }
    }

    /**
     * Switches off the frosted band WeChat paints along the bottom of a page.
     *
     * <p>New in 8.0.72: every page is a {@code FrostedContentView} which blurs a
     * strip of its own content the height of the docked tab bar, so the bar
     * reads as frosted glass over the page behind it. Once the bar floats, that
     * strip has nothing sitting on it any more and shows up on its own — a band
     * across the bottom of the screen, lighter than the page, running the full
     * width under the pill. It is painted in {@code dispatchDraw} rather than by
     * a child view, which is why it never shows up in a view-tree dump.
     *
     * <p>Only the bottom band is dropped. The same view frosts a strip under the
     * status bar, and that one still sits behind a real bar — it is WeChat's own
     * look and none of our business.
     *
     * <p>Re-applied every frame rather than once, because setting it is not
     * final: the setter only writes the field, and WeChat re-applies the whole
     * frosted configuration (its {@code a(boolean, int, float)}) on theme and
     * layout changes, which puts the band straight back. Running on the same
     * pre-draw pass as {@link #keepNavBarClear()} means the value is right for
     * the frame about to be drawn, whenever it was last clobbered.
     *
     * <p>Resolved by method name rather than by type: the class is WeChat's own
     * and absent from QQ and from every WeChat before 8.0.72, which land on the
     * NoSuchMethod path once and are never probed again.
     */
    private static void dropBottomFrostAll() {
        if (LiquidGlassModule.app() == HostApp.PDD) {
            return; // no frosted band exists in PDD's pages
        }
        ViewGroup pager = sPagerRef.get();
        if (pager == null) {
            return;
        }
        for (int i = 0; i < pager.getChildCount(); i++) {
            dropBottomFrost(pager.getChildAt(i));
        }
    }

    /** Per-page-class cache for WeChat's optional frosted-band API. */
    private static final java.util.HashMap<Class<?>, Method[]> sFrostMethods =
            new java.util.HashMap<>();
    private static final java.util.HashSet<Class<?>> sNoFrostClasses =
            new java.util.HashSet<>();
    private static boolean sFrostLogged;

    private static void dropBottomFrost(View page) {
        Class<?> pageClass = page.getClass();
        Method[] methods = sFrostMethods.get(pageClass);
        if (methods == null && !sNoFrostClasses.contains(pageClass)) {
            try {
                methods = new Method[]{
                        pageClass.getMethod("getBottomBlurAreaHeight"),
                        pageClass.getMethod("setBottomBlurAreaHeight", int.class),
                };
                sFrostMethods.put(pageClass, methods);
            } catch (NoSuchMethodException e) {
                sNoFrostClasses.add(pageClass);
            } catch (Throwable t) {
                sNoFrostClasses.add(pageClass);
                LiquidGlassModule.logErr("frosted band lookup", t);
            }
        }
        if (methods == null) {
            return;
        }
        try {
            Object h = methods[0].invoke(page);
            if (!(h instanceof Integer) || (Integer) h == 0) {
                return;
            }
            methods[1].invoke(page, 0);
            if (!sFrostLogged) {
                sFrostLogged = true;
                LiquidGlassModule.log(android.util.Log.INFO,
                        "dropping the pages' " + h + "px frosted bottom band");
            }
        } catch (Throwable ignored) {
            // Per-frame path: a broken page must not spam the log.
        }
    }

    /**
     * @param targetHeight height the page is being stretched to. Passed in
     *     rather than read off the page because the stretch above only takes
     *     effect on the next layout pass, so the page still reports the old,
     *     short height on this one.
     */
    private static void extendOnePage(ViewGroup pg, int targetHeight) {
        int pageHeight = Math.max(pg.getHeight(), targetHeight);
        if (pageHeight <= 0) {
            return;
        }
        // Some pages (Contacts) reserve the bar's height as padding on the page
        // itself rather than as a child margin, which caps the content at 2434
        // no matter what its LayoutParams say. Drop it and let the scrollers
        // carry the inset instead.
        if (pg.getPaddingBottom() > 0) {
            pg.setClipToPadding(false);
            pg.setPadding(pg.getPaddingLeft(), pg.getPaddingTop(),
                    pg.getPaddingRight(), 0);
        }
        for (int i = 0; i < pg.getChildCount(); i++) {
            View c = pg.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE
                    || c.getHeight() < pageHeight / 2) {
                continue;
            }
            stretchToBottom(c, pageHeight);
            keepStretchedToBottom(c);
        }
        padScrollersBottom(pg, bottomReserve(pg), 0);
    }

    /** Breathing room between the last row and the pill once it is scrolled clear. */
    private static final float LAST_ROW_GAP_DP = 8f;

    /**
     * Room the floating bar takes up at the bottom of the screen.
     *
     * <p>Measured off the pill rather than assembled from the constants that
     * position it, so it stays right whatever the bar height, the float offset
     * or the navigation inset turn out to be.
     */
    private static int bottomReserve(View anchor) {
        LiquidGlassHostLayout host = sHostRef.get();
        if (host == null || host.getHeight() <= 0) {
            return 0;
        }
        // Unscaled: the pill grows while held and eases in on reveal, and a
        // stretch that ran mid-way through either used to hand the lists a
        // padding they had to relayout for, twice.
        ViewGeom.unscaledScreenPos(host, sLoc);
        // Undo the offset followBarOffset applies while the bar slides away.
        float pillTop = sLoc[1] - host.getTranslationY() + host.getPaddingTop();
        View root = host.getRootView();
        if (root == null || root.getHeight() <= 0) {
            return 0;
        }
        root.getLocationOnScreen(sLoc);
        float density = anchor.getResources().getDisplayMetrics().density;
        float reserve = (sLoc[1] + root.getHeight()) - pillTop
                + LAST_ROW_GAP_DP * density;
        return reserve > 0f ? Math.round(reserve) : 0;
    }

    private static void stretchToBottom(View c, int pageHeight) {
        if (pageHeight <= 0) {
            return;
        }
        int gap = pageHeight - c.getBottom();
        if (gap <= 8) {
            return; // already reaches the bottom
        }
        ViewGroup.LayoutParams lp = c.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
        boolean changed = false;
        if (mlp.bottomMargin != 0) {
            mlp.bottomMargin = 0;
            changed = true;
        }
        if (mlp.height >= 0) {
            mlp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            changed = true;
        }
        if (changed) {
            c.setLayoutParams(mlp);
        }
    }

    /** Re-applies a bottom stretch when the host page restores its docked size. */
    private static void keepStretchedToBottom(View c) {
        if (Boolean.TRUE.equals(c.getTag(EXTEND_TAG_KEY))) {
            return;
        }
        c.setTag(EXTEND_TAG_KEY, Boolean.TRUE);
        c.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> {
            ViewGroup parent = v.getParent() instanceof ViewGroup
                    ? (ViewGroup) v.getParent() : null;
            if (parent == null) {
                return;
            }
            dropParentBottomReserve(parent, v);
            ViewGroup grand = parent.getParent() instanceof ViewGroup
                    ? (ViewGroup) parent.getParent() : null;
            if (grand != null) {
                stretchToBottom(parent, grand.getHeight());
            }
            stretchToBottom(v, Math.max(parent.getHeight(),
                    grand == null ? 0 : grand.getHeight()));
        });
    }

    /**
     * Removes a docked-bar reserve expressed as padding on the scroller's parent.
     *
     * <p>QQ's Dynamic page uses a full-height {@code QZoneBaseBlockContainer}
     * with 152px bottom padding, so its MATCH_PARENT feed stops at 2628px inside
     * a 2780px page. The feed gets its own scroll padding below, therefore this
     * outer reserve must be dropped just like page-level bottom padding.
     */
    private static void dropParentBottomReserve(ViewGroup parent, View child) {
        int gap = parent.getHeight() - child.getBottom();
        int reserve = parent.getPaddingBottom();
        ViewGroup pager = sPagerRef.get();
        int pageHeight = pager == null ? parent.getHeight() : pager.getHeight();
        if (child.getTop() > 8 || gap <= 8 || reserve <= 0
                || Math.abs(parent.getHeight() - pageHeight) > 8
                || (sBarHeight > 0 && Math.abs(gap - sBarHeight) > 8)
                || Math.abs(reserve - gap) > 8) {
            return;
        }
        int remaining = Math.max(0, reserve - gap);
        parent.setClipToPadding(false);
        parent.setPadding(parent.getPaddingLeft(), parent.getPaddingTop(),
                parent.getPaddingRight(), remaining);
    }

    /**
     * Room the scrollers were last padded with, and the scrollers themselves.
     *
     * <p>Kept so the per-frame path can re-assert padding and clipping without
     * walking the tree again: the apps restore their own view state on layout
     * passes of their own, and a scroller that has quietly gone back to
     * {@code clipToPadding=true} paints its padding band as an opaque strip —
     * the white block that appears over the content partway through a scroll.
     */
    private static final java.util.ArrayList<WeakReference<ViewGroup>> sPaddedScrollers =
            new java.util.ArrayList<>();
    private static int sScrollerPad;
    private static boolean sFeedExpandLogged;
    private static boolean sFeed2Logged;
    private static boolean sFeed4Logged;
    private static boolean sPddOffscreenLogged;
    private static int sPddExpandFrame;
    private static final int MAX_TRACKED_SCROLLERS = 16;

    /**
     * Back-to-top button lift (PDD home feed).
     *
     * <p>PDD shows a floating 回顶部 button once the home feed is scrolled down. In stock PDD it
     * sits just above the native bar — exactly where our pill floats — so without a lift it ends
     * up hidden behind the pill. The per-frame path finds the button, remembers its resting
     * translation, and raises it just enough that its bottom clears the pill top.
     *
     * <p>The lift is computed from the button's UNTRANSLATED bottom: deriving it from the drawn
     * position instead would flip the translation back to rest on the next frame and make the
     * button oscillate. When the button leaves the screen or is detached, its translation is
     * restored and the reference dropped.
     */
    private static WeakReference<View> sBackTopRef = new WeakReference<>(null);
    private static float sBackTopBaseTranslationY;
    private static boolean sBackTopHasBase;
    private static long sBackTopLastProbeMs;
    private static boolean sBackTopLogged;
    private static final long BACK_TOP_PROBE_MS = 120L;
    private static final int BACK_TOP_MIN_DP = 34;
    private static final int BACK_TOP_MAX_DP = 112;
    private static final int BACK_TOP_GAP_DP = 8;
    private static final int BACK_TOP_MAX_DEPTH = 14;
    private static final int BACK_TOP_MAX_NODES = 1200;

    /** Re-asserts the scroll padding and the clipping flag on every frame. */
    private static void maintainScrollerPadding() {
        // A 0 pad is a real target now (PDD's feed expand),
        // not "no pad known yet". Only skip when nothing is tracked at all.
        if (sPaddedScrollers.isEmpty()) {
            return;
        }
        int pad = sScrollerPad;
        for (int i = 0; i < sPaddedScrollers.size(); i++) {
            ViewGroup v = sPaddedScrollers.get(i).get();
            if (v == null) {
                continue;
            }
            if (v.getClipToPadding()) {
                v.setClipToPadding(false);
            }
            if (v.getPaddingBottom() != pad) {
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                        v.getPaddingRight(), pad);
            }
            // A MATCH_PARENT list only reaches the parent's bottom if the
            // parent has no bottom reserve of its own. PDD puts the
            // reserve back on the page on layout passes, so keep the
            // tracked scroller's parent clear of it too, every frame.
            if (LiquidGlassModule.app() == HostApp.PDD) {
                android.view.ViewParent parent = v.getParent();
                if (parent instanceof ViewGroup) {
                    ViewGroup pv = (ViewGroup) parent;
                    if (pv.getClipToPadding()) {
                        pv.setClipToPadding(false);
                    }
                    if (pv.getPaddingBottom() != 0) {
                        pv.setPadding(pv.getPaddingLeft(), pv.getPaddingTop(),
                                pv.getPaddingRight(), 0);
                    }
                }
            }
        }
    }

    private static void trackPaddedScroller(ViewGroup scroller) {
        for (int i = 0; i < sPaddedScrollers.size(); i++) {
            if (sPaddedScrollers.get(i).get() == scroller) {
                return;
            }
        }
        if (sPaddedScrollers.size() >= MAX_TRACKED_SCROLLERS) {
            sPaddedScrollers.remove(0);
        }
        sPaddedScrollers.add(new WeakReference<>(scroller));
    }

    /** Lifts PDD's floating back-to-top button above the pill; no-op on other hosts. */
    private static void liftBackToTopButton(LiquidGlassHostLayout host) {
        if (LiquidGlassModule.app() != HostApp.PDD) {
            return;
        }
        if (host == null || !host.isAttachedToWindow() || host.getWidth() <= 0) {
            return;
        }
        View target = sBackTopRef.get();
        if (target == null || !target.isAttachedToWindow() || !target.isShown()) {
            if (target != null) {
                restoreBackTop(target);
                sBackTopRef = new WeakReference<>(null);
            }
            long now = SystemClock.uptimeMillis();
            if (now - sBackTopLastProbeMs < BACK_TOP_PROBE_MS) {
                return;
            }
            sBackTopLastProbeMs = now;
            View root = host.getRootView();
            if (!(root instanceof ViewGroup)) {
                return;
            }
            float density = host.getResources().getDisplayMetrics().density;
            host.getLocationOnScreen(sLoc);
            int pillTop = sLoc[1];
            target = findBackToTopButton((ViewGroup) root, density, pillTop, root.getWidth());
            if (target == null) {
                return;
            }
            sBackTopRef = new WeakReference<>(target);
            sBackTopHasBase = false;
            if (!sBackTopLogged) {
                sBackTopLogged = true;
                LiquidGlassModule.log(android.util.Log.INFO,
                        "pdd back-to-top found: " + target.getClass().getName()
                                + " " + target.getWidth() + "x" + target.getHeight());
            }
        }
        if (!sBackTopHasBase) {
            sBackTopBaseTranslationY = target.getTranslationY();
            sBackTopHasBase = true;
        }
        float density = host.getResources().getDisplayMetrics().density;
        host.getLocationOnScreen(sLoc);
        int desiredBottom = sLoc[1] - Math.round(BACK_TOP_GAP_DP * density);
        target.getLocationOnScreen(sLoc);
        int drawnBottom = sLoc[1] + target.getHeight();
        // Untranslated bottom: the drawn position already includes our lift.
        int naturalBottom = drawnBottom
                - Math.round(target.getTranslationY() - sBackTopBaseTranslationY);
        float lift = Math.max(0, naturalBottom - desiredBottom);
        target.setTranslationY(sBackTopBaseTranslationY - lift);
    }

    private static void restoreBackTop(View target) {
        if (target == null || !sBackTopHasBase) {
            return;
        }
        try {
            target.setTranslationY(sBackTopBaseTranslationY);
        } catch (Throwable ignored) {
        }
        sBackTopHasBase = false;
    }

    /**
     * Finds PDD's floating back-to-top button under {@code root}.
     *
     * <p>First pass matches the button's own label/id ("回顶部", "顶部", "to_top", "back_top",
     * "backtop") and uses the clickable ancestor that carries the touch target. Only if nothing
     * is labelled, a second pass accepts a small square clickable view in the bottom-right that
     * would overlap the pill. The walk is bounded (depth and node caps) so the probe stays
     * cheap enough for the UI thread.
     */
    private static View findBackToTopButton(ViewGroup root, float density, int pillTop,
            int rootWidth) {
        int minPx = Math.round(BACK_TOP_MIN_DP * density);
        int maxPx = Math.round(BACK_TOP_MAX_DP * density);
        int[] count = new int[1];
        View labelled = findBackToTopButton(root, 0, minPx, maxPx, pillTop, rootWidth, count,
                false);
        if (labelled != null) {
            return labelled;
        }
        count[0] = 0;
        return findBackToTopButton(root, 0, minPx, maxPx, pillTop, rootWidth, count, true);
    }

    private static View findBackToTopButton(ViewGroup group, int depth, int minPx, int maxPx,
            int pillTop, int rootWidth, int[] count, boolean fallback) {
        if (group == null || depth > BACK_TOP_MAX_DEPTH || count[0] > BACK_TOP_MAX_NODES) {
            return null;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null || !child.isShown()) {
                continue;
            }
            count[0]++;
            if (count[0] > BACK_TOP_MAX_NODES) {
                return null;
            }
            if (child == sHostRef.get() || child == sTabViewRef.get()) {
                continue;
            }
            if (!fallback) {
                View hit = backTopTargetFrom(child, minPx, maxPx);
                if (hit != null) {
                    return hit;
                }
            } else if (child.isClickable() && child.getWidth() >= minPx
                    && child.getWidth() <= maxPx && child.getHeight() >= minPx
                    && child.getHeight() <= maxPx) {
                child.getLocationOnScreen(sLoc);
                int centerX = sLoc[0] + child.getWidth() / 2;
                int bottom = sLoc[1] + child.getHeight();
                if (centerX >= rootWidth * 0.72f && bottom > pillTop
                        && Math.abs(child.getWidth() - child.getHeight()) <= maxPx / 2) {
                    return child;
                }
            }
            if (child instanceof ViewGroup) {
                View found = findBackToTopButton((ViewGroup) child, depth + 1, minPx, maxPx,
                        pillTop, rootWidth, count, fallback);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Returns the clickable sized ancestor of a back-to-top-labelled view, or null. */
    private static View backTopTargetFrom(View view, int minPx, int maxPx) {
        if (!matchesBackTopLabel(view)) {
            return null;
        }
        View current = view;
        for (int i = 0; i < 5 && current != null; i++) {
            if (current.isClickable() && current.getWidth() >= minPx
                    && current.getWidth() <= maxPx && current.getHeight() >= minPx
                    && current.getHeight() <= maxPx) {
                return current;
            }
            android.view.ViewParent parent = current.getParent();
            current = (parent instanceof View) ? (View) parent : null;
        }
        return null;
    }

    private static boolean matchesBackTopLabel(View view) {
        CharSequence label = null;
        if (view instanceof TextView) {
            label = ((TextView) view).getText();
        }
        if (label == null || label.length() == 0) {
            label = view.getContentDescription();
        }
        if (label != null) {
            String text = label.toString();
            if (text.contains("回顶部") || text.contains("顶部")) {
                return true;
            }
        }
        String idName = null;
        try {
            if (view.getId() != View.NO_ID) {
                idName = view.getResources().getResourceEntryName(view.getId());
            }
        } catch (Throwable ignored) {
        }
        if (idName != null) {
            String lower = idName.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("back_top") || lower.contains("backtop")
                    || lower.contains("to_top")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Stretches PDD's content to the parent's full height. The backdrop
     * (content FrameLayout) is 2464px on a 2800px parent — it ends 336px
     * above the parent's bottom, so the feed can never reach the strip
     * below the pill no matter how its padding is cleared. Stretch the
     * backdrop, then the CustomViewPager and its pages, so the feed can
     * extend into the strip.
     */
    private static void stretchPddContentToBottom(ViewGroup pager) {
        if (pager == null) {
            return;
        }
        ViewGroup pagerParent = pager.getParent() instanceof ViewGroup
                ? (ViewGroup) pager.getParent() : null;
        if (pagerParent == null || pagerParent.getHeight() <= 0) {
            return;
        }
        int parentHeight = pagerParent.getHeight();
        stretchToBottom(pager, parentHeight);
        ViewGroup viewPager = findPddViewPager(pager);
        if (viewPager != null) {
            int pageTarget = parentHeight - viewPager.getTop();
            stretchToBottom(viewPager, parentHeight);
            for (int i = 0; i < viewPager.getChildCount(); i++) {
                View page = viewPager.getChildAt(i);
                if (page instanceof ViewGroup) {
                    stretchToBottom(page, pageTarget);
                    // The page is full height now, but the feed container
                    // inside it may still be short, so the items end above
                    // the strip even though the page reaches it. Stretch
                    // each page's direct children to the page's full
                    // height too.
                    for (int j = 0; j < ((ViewGroup) page).getChildCount(); j++) {
                        View inner = ((ViewGroup) page).getChildAt(j);
                        if (inner instanceof ViewGroup) {
                            stretchToBottom(inner, pageTarget);
                        }
                    }
                    // Stretch the feed scroller itself to the full page
                    // target. It already fills the short page, so a
                    // plain "fill the parent" pass would skip it — it
                    // must be stretched to the target height, or the strip
                    // (below the scroller) stays black on every tab switch.
                    stretchScrollerInPage((ViewGroup) page, pageTarget);
                }
            }
            // Keep every tab's page alive so a switch never has to create
            // a short page on demand. The ViewPager only keeps current
            // ± 1 pages by default, so jumping home (0) → chat (3) creates
            // the chat page fresh (short) and the strip flashes black
            // until a later pass stretches it. With an offscreen limit of
            // 4 all 5 pages are children from install, pre-stretched, and
            // a switch shows an already-full page. Reflection:
            // CustomViewPager may not expose the method directly.
            if (!sPddOffscreenLogged) {
                sPddOffscreenLogged = true;
                try {
                    java.lang.reflect.Method m = null;
                    Class<?> c = viewPager.getClass();
                    while (c != null && m == null) {
                        try {
                            m = c.getDeclaredMethod(
                                    "setOffscreenPageLimit", int.class);
                        } catch (NoSuchMethodException e) {
                            c = c.getSuperclass();
                        }
                    }
                    if (m != null) {
                        m.setAccessible(true);
                        m.invoke(viewPager, 4);
                        LiquidGlassModule.log(android.util.Log.INFO,
                                "pdd offscreen: set to 4 on "
                                + viewPager.getClass().getSimpleName());
                    } else {
                        LiquidGlassModule.log(android.util.Log.INFO,
                                "pdd offscreen: method NOT FOUND on "
                                + viewPager.getClass().getSimpleName());
                    }
                } catch (Exception e) {
                    LiquidGlassModule.log(android.util.Log.INFO,
                            "pdd offscreen: FAILED " + e);
                    // Offscreen limit unavailable — the stretch + 5-frame
                    // re-run still apply; only the pre-creation benefit is lost.
                }
            }
        }
        if (!sFeed4Logged) {
            sFeed4Logged = true;
            LiquidGlassModule.log(android.util.Log.INFO,
                    "pdd stretch: parentH=" + parentHeight
                    + " pagerH=" + pager.getHeight()
                    + " viewPager=" + (viewPager == null ? "null"
                    : viewPager.getClass().getSimpleName()
                    + " h=" + viewPager.getHeight()
                    + " top=" + viewPager.getTop()));
        }
    }

    /** Walks a page's subtree for its feed scroller and stretches it to
     * the page's full target height. Stretches the scroller if one is
     * found, otherwise the LARGEST ViewGroup in the page (the feed
     * container, whatever its class). The home feed scroller
     * (HomeScrollSpeedOptProductListView) gets an explicit pixel height:
     * MATCH_PARENT does not stick for it because its ancestors are
     * WRAP_CONTENT, so PDD re-measures it back to a shorter height every
     * layout. Other tabs' scrollers/containers keep MATCH_PARENT —
     * forcing them to a pixel height makes the profile feed vanish. */
    private static void stretchScrollerInPage(ViewGroup page, int pageTarget) {
        java.util.ArrayDeque<ViewGroup> queue = new java.util.ArrayDeque<>();
        queue.add(page);
        int seen = 0;
        ViewGroup largest = null;
        int largestH = -1;
        while (!queue.isEmpty() && seen < 100) {
            ViewGroup g = queue.poll();
            seen++;
            if (g != page) {
                if (isScroller(g)) {
                    // Explicit height only for the home feed scroller.
                    // Other tabs' scrollers/containers keep MATCH_PARENT —
                    // forcing them to a pixel height makes the profile
                    // feed vanish.
                    if (("com.xunmeng.pinduoduo.app_default_home.widget"
                            + ".HomeScrollSpeedOptProductListView")
                            .equals(g.getClass().getName())) {
                        setExplicitHeight(g, pageTarget);
                        LiquidGlassModule.log(android.util.Log.INFO,
                                "pdd home scroller: "
                                + g.getClass().getSimpleName()
                                + " h=" + g.getHeight()
                                + " -> " + pageTarget);
                    } else {
                        stretchToBottom(g, pageTarget);
                        LiquidGlassModule.log(android.util.Log.INFO,
                                "pdd scroller: "
                                + g.getClass().getSimpleName()
                                + " h=" + g.getHeight()
                                + " -> " + pageTarget);
                    }
                    return;
                }
                if (g.getHeight() > largestH) {
                    largestH = g.getHeight();
                    largest = g;
                }
            }
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c instanceof ViewGroup) {
                    queue.add((ViewGroup) c);
                }
            }
        }
        if (largest != null && largestH > 0) {
            stretchToBottom(largest, pageTarget);
            LiquidGlassModule.log(android.util.Log.INFO,
                    "pdd largest: "
                    + largest.getClass().getSimpleName()
                    + " h=" + largestH + " -> " + pageTarget);
        }
    }

    /** Forces a view to an explicit pixel height (plus bottomMargin 0),
     * instead of MATCH_PARENT which WRAP_CONTENT ancestors ignore on
     * re-measure. Used only for the home feed scroller. */
    private static void setExplicitHeight(View v, int heightPx) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) {
            return;
        }
        boolean changed = false;
        if (lp.height != heightPx) {
            lp.height = heightPx;
            changed = true;
        }
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp =
                    (ViewGroup.MarginLayoutParams) lp;
            if (mlp.bottomMargin != 0) {
                mlp.bottomMargin = 0;
                changed = true;
            }
        }
        if (changed) {
            v.setLayoutParams(lp);
        }
    }

    /** Bounded breadth-first walk for the CustomViewPager in the subtree. */
    private static ViewGroup findPddViewPager(ViewGroup root) {
        java.util.ArrayDeque<ViewGroup> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        int seen = 0;
        while (!queue.isEmpty() && seen < 200) {
            ViewGroup g = queue.poll();
            seen++;
            if (g.getClass().getName().contains("ViewPager")) {
                return g;
            }
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c instanceof ViewGroup) {
                    queue.add((ViewGroup) c);
                }
            }
        }
        return null;
    }

    /**
     * Gives every scrolling view in the subtree room to scroll its last row clear
     * of the floating pill, with {@code clipToPadding=false} so rows still render
     * through the padded band — i.e. behind and below the pill.
     */
    private static void padScrollersBottom(ViewGroup root, int pad, int depth) {
        if (depth > 12) {
            return;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            // ViewPager2 is backed by a RecyclerView, but that RecyclerView is
            // only the horizontal page host. Treating it as the page scroller
            // stops the walk here and leaves QQ's real BounceScrollView several
            // levels below untouched. Pass through pager RecyclerViews and pad
            // the first actual scrolling container inside each page instead.
            if (isViewPagerRecycler(c)) {
                padScrollersBottom((ViewGroup) c, pad, depth + 1);
            } else if (isScroller(c)) {
                ViewGroup sv = (ViewGroup) c;
                // QQ's Dynamic page nests its real feed RecyclerView several
                // containers below the page root and still measures it to the
                // old docked bar. Its parent owns that reserve as bottom padding,
                // so remove it before stretching the feed itself. Only touch a
                // top-anchored scroller; title-offset lists keep their origin.
                int parentHeight = root.getHeight();
                if (LiquidGlassModule.app() == HostApp.PDD) {
                    // PDD's feed starts below the search bar (top > 8), so
                    // the top-anchored guard above would skip the stretch
                    // and the list would stay short. For PDD drop the
                    // parent's bottom reserve outright and stretch the list
                    // to the parent's bottom whatever its top (the top
                    // stays where it is; only the bottom extends).
                    if (root.getPaddingBottom() > 0) {
                        root.setClipToPadding(false);
                        root.setPadding(root.getPaddingLeft(),
                                root.getPaddingTop(), root.getPaddingRight(), 0);
                    }
                    if (parentHeight > 0
                            && parentHeight - c.getBottom() > 8) {
                        stretchToBottom(c, parentHeight);
                        if (!sFeed2Logged) {
                            sFeed2Logged = true;
                            LiquidGlassModule.log(android.util.Log.INFO,
                                    "pdd expand: stretched "
                                    + c.getClass().getSimpleName()
                                    + " top=" + c.getTop()
                                    + " bottom=" + c.getBottom()
                                    + " parentH=" + parentHeight);
                        }
                    }
                } else if (c.getTop() <= 8
                        && parentHeight - c.getBottom() > 8) {
                    dropParentBottomReserve(root, c);
                    stretchToBottom(c, parentHeight);
                    keepStretchedToBottom(c);
                }
                // clipToPadding is re-asserted even when the amount already
                // matches: WeChat turns it back on, and with it on the padded
                // band goes blank instead of showing rows through the glass.
                if (sv.getClipToPadding()) {
                    sv.setClipToPadding(false);
                }
                if (sv.getPaddingBottom() != pad) {
                    sv.setPadding(sv.getPaddingLeft(), sv.getPaddingTop(),
                            sv.getPaddingRight(), pad);
                }
                // Track even when pad is 0 (PDD's feed expand). A 0 pad
                // still needs the per-frame re-assert or PDD puts its
                // bottom reserve back on a layout pass.
                if (pad >= 0) {
                    sScrollerPad = pad;
                    trackPaddedScroller(sv);
                }
            } else if (c instanceof ViewGroup) {
                padScrollersBottom((ViewGroup) c, pad, depth + 1);
            }
        }
    }

    /** The RecyclerView used internally by ViewPager2 scrolls between pages. */
    private static boolean isViewPagerRecycler(View v) {
        if (!(v instanceof ViewGroup)) {
            return false;
        }
        for (Class<?> k = v.getClass(); k != null; k = k.getSuperclass()) {
            if ("androidx.viewpager2.widget.ViewPager2$RecyclerViewImpl"
                    .equals(k.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recognises scrolling containers without compile-time access to androidx,
     * walking the superclass chain so WeChat's own subclasses match too.
     */
    private static boolean isScroller(View v) {
        if (!(v instanceof ViewGroup)) {
            return false;
        }
        if (v instanceof android.widget.ScrollView
                || v instanceof android.widget.AbsListView) {
            return true;
        }
        for (Class<?> k = v.getClass(); k != null; k = k.getSuperclass()) {
            String n = k.getName();
            if ("androidx.recyclerview.widget.RecyclerView".equals(n)
                    || "androidx.core.widget.NestedScrollView".equals(n)) {
                return true;
            }
        }
        return false;
    }

    /** Lets the droplet's overflow escape every ancestor up to the content view. */
    private static void unclipAncestors(ViewGroup from) {
        ViewGroup v = from;
        for (int i = 0; i < 12 && v != null; i++) {
            v.setClipChildren(false);
            v.setClipToPadding(false);
            if (v.getId() == android.R.id.content) {
                return;
            }
            android.view.ViewParent p = v.getParent();
            v = p instanceof ViewGroup ? (ViewGroup) p : null;
        }
    }

    /** Finds the app-owned icon view inside one tab. */
    private static View findTabIcon(View v) {
        // Field first where the app exposes one: PDD's icon is a plain
        // ImageView that no class test can single out, but the tab item's own
        // tabImageView field names it exactly.
        View viaField = TabBarBridge.tabIconViaField(v);
        if (viaField != null) {
            return viaField;
        }
        HostApp app = LiquidGlassModule.app();
        if (app != null && app.isTabIconClass(v.getClass().getName())) {
            return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findTabIcon(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Width of the widest visible leaf inside a tab column.
     *
     * <p>Measuring the column with an UNSPECIFIED spec is not dependable on its
     * own. WeChat's tab is a RelativeLayout wrapping a chain of MATCH_PARENT
     * children, and a MATCH_PARENT child under an unspecified parent resolves to
     * an UNSPECIFIED spec of size zero — so what comes back is whatever that
     * chain collapses to rather than the label's own width, and the pill ends up
     * hugging a value several times too small. The row is already laid out by the
     * time this runs and its leaves — the icon and the label — carry their real
     * widths, so they are read straight off instead.
     *
     * <p>Only visible leaves count: the unread badge and the red dot sit at
     * INVISIBLE rather than GONE, so they are still laid out, and they are wide
     * enough to stretch the column if allowed to.
     */
    private static int leafContentWidth(View v) {
        if (v.getVisibility() != View.VISIBLE) {
            return 0;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int widest = 0;
            for (int i = 0; i < g.getChildCount(); i++) {
                widest = Math.max(widest, leafContentWidth(g.getChildAt(i)));
            }
            return widest;
        }
        // A MATCH_PARENT leaf is only ever as wide as the column it was handed,
        // so it says nothing about what is drawn inside it. QQ's icon is one —
        // it fills the whole 305px column while the glyph in it is about 55px —
        // and counting it would size every column to the screen quarter it
        // already had, which is the opposite of hugging the content.
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null && lp.width == ViewGroup.LayoutParams.MATCH_PARENT) {
            return 0;
        }
        return v.getWidth();
    }

    /**
     * Replaces the app's equal-weight tab columns with fixed, content-sized ones.
     *
     * <p>Both apps give each tab {@code LinearLayout.LayoutParams(0, h, weight=1)}
     * — WeChat in its own layout, QQ by way of {@code TabWidget.addView} — which
     * only makes sense when the bar spans the screen. For a floating pill the
     * width has to come from the content instead, mirroring KernelSU, where the
     * row is {@code IntrinsicSize.Min} and every tab shares the widest column's
     * width.
     */
    private static int hugContentWidth(ViewGroup tabRow, float density) {
        if (tabRow == null || tabRow.getChildCount() == 0) {
            return 0;
        }
        int childCount = tabRow.getChildCount();
        int count = 0;
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        int measured = 0;
        int leaf = 0;
        int slot = 0;
        for (int i = 0; i < childCount; i++) {
            View tab = tabRow.getChildAt(i);
            if (tab.getVisibility() == View.GONE) {
                continue;
            }
            count++;
            tab.measure(unspecified, unspecified);
            measured = Math.max(measured, tab.getMeasuredWidth());
            leaf = Math.max(leaf, leafContentWidth(tab));
            slot = Math.max(slot, tab.getWidth());
        }
        if (count == 0) {
            return 0;
        }
        // An unbounded measure that comes back wider than the column the tab is
        // already laid out in has not measured content at all — it has fallen
        // over. QQ reads 737px for a 305px column, because measuring a
        // MATCH_PARENT child against UNSPECIFIED is undefined and its
        // RelativeLayout answers with something unrelated. WeChat's 105px for
        // the same 305px column is a real content width and is kept.
        if (slot > 0 && measured > slot) {
            LiquidGlassModule.log(android.util.Log.INFO,
                    "unbounded measure returned " + measured + " for a " + slot
                            + "px column; using the leaf width instead");
            measured = 0;
        }
        // Whichever reads wider. Each can come back short on its own — the
        // measure when the layout will not measure unbounded, the leaves when
        // the widest thing in the tab sizes itself to the column — so the
        // maximum is the one that holds.
        int widest = Math.max(measured, leaf);
        if (widest <= 0) {
            return 0;
        }
        // WeChat's labels are narrower than KernelSU's, so hugging them exactly
        // gives a cramped 46%-wide bar. Matching KernelSU's proportions means
        // giving each column the same generous breathing room it uses (~32dp per
        // side), then capping so the pill still clears the screen edges.
        int pad = Math.round(density * 4f);
        int tabWidth = widest + Math.round(density * 32f);
        int screen = tabRow.getResources().getDisplayMetrics().widthPixels;
        int maxTotal = screen - Math.round(density * 24f);
        if (tabWidth * count + pad * 2 > maxTotal) {
            tabWidth = (maxTotal - pad * 2) / count;
        }
        for (int i = 0; i < childCount; i++) {
            View tab = tabRow.getChildAt(i);
            if (tab.getVisibility() == View.GONE) {
                continue;
            }
            ViewGroup.LayoutParams lp = tab.getLayoutParams();
            if (lp == null) {
                continue;
            }
            // Skip the write when nothing would change: setLayoutParams
            // forces a layout pass, and re-asserting identical widths on
            // every structure refresh is pure churn under the pill.
            boolean lpChanged = lp.width != tabWidth;
            lp.width = tabWidth;
            if (lp instanceof android.widget.LinearLayout.LayoutParams) {
                android.widget.LinearLayout.LayoutParams llp =
                        (android.widget.LinearLayout.LayoutParams) lp;
                if (llp.weight != 0f) {
                    llp.weight = 0f;
                    lpChanged = true;
                }
            }
            if (lpChanged) {
                tab.setLayoutParams(lp);
            }
        }
        // Horizontal inset only. Vertical padding is the caller's: PDD
        // carries its extra pill height as symmetric top/bottom padding,
        // and zeroing it here would put the tabs back against one edge.
        tabRow.setPadding(pad, tabRow.getPaddingTop(),
                pad, tabRow.getPaddingBottom());
        int total = tabWidth * count + pad * 2;
        LiquidGlassModule.log(android.util.Log.INFO,
                "tab row hugged: content=" + widest
                        + " (measured=" + measured + " leaf=" + leaf + ")"
                        + " tabWidth=" + tabWidth
                        + " total=" + total + " screen=" + screen);
        return total;
    }

    /**
     * Keeps the glass and shadow travelling with WeChat's own bar.
     *
     * <p>WeChat slides the bar out of the way with {@code translationY} — that is
     * how it gets out of the way for the mini-program panel — and fades it with
     * alpha. Since only the bar itself was reparented into the host, those
     * properties would otherwise move the tabs while the glass and its shadow
     * stayed put. Mirroring them onto our own layers keeps the pill whole, and
     * reading rather than overwriting them leaves WeChat's animator alone.
     */
    /**
     * How much further than WeChat the bar has to travel to leave the screen,
     * as a fraction of WeChat's own travel. Zero once the pill already clears.
     */
    private static float hideShortfall(LiquidGlassHostLayout host, View tabView) {
        float travel = tabView.getHeight();
        if (travel <= 0f) {
            return 0f;
        }
        host.getLocationOnScreen(sLoc);
        // Undo the offset this very method applied, so the reference stays put.
        float pillTop = sLoc[1] - host.getTranslationY() + host.getPaddingTop();
        View root = host.getRootView();
        root.getLocationOnScreen(sLoc);
        float need = sLoc[1] + root.getHeight() - pillTop;
        return Math.max(0f, need / travel - 1f);
    }

    private static void followBarOffset(LiquidGlassHostLayout host) {
        View tabView = sTabViewRef.get();
        if (tabView == null) {
            return;
        }
        // Both apps repaint the bar's own background when the theme changes —
        // QQ through its skin engine, which hands the bar a fresh opaque
        // drawable and would bury the glass under it. Nothing announces that,
        // so it is re-checked here; the common case is a null field read.
        if (tabView.getBackground() != null) {
            stripSolidBackgrounds(tabView);
        }
        if (LiquidGlassModule.app() == HostApp.PDD) {
            // PDD animates its bar's translationY while the feed scrolls —
            // full-height excursions, back and forth (the 1.0.3 diagnostics:
            // barTy flapping 0 ↔ 193 while the host never moved). Docked,
            // that is the app's own elasticity; inside a floating pill it
            // reads as the bar falling out of the glass. The pill is a fixed
            // navigation surface, so inside it the bar's position and
            // opacity belong to the host: pin both, every frame.
            if (tabView.getTranslationY() != 0f) {
                tabView.setTranslationY(0f);
            }
            if (tabView.getAlpha() != 1f) {
                tabView.setAlpha(1f);
            }
        }
        float ty = tabView.getTranslationY();
        float alpha = tabView.getAlpha();
        boolean gone = tabView.getVisibility() != View.VISIBLE;

        // WeChat slides its bar down by exactly its own height, which was enough
        // to clear the bottom of the screen when the bar sat flush against it.
        // The pill floats above that, so the same travel leaves a slice of it
        // still showing when WeChat gives up and hides the bar outright — it
        // vanishes mid-slide. The shortfall is added to the host rather than to
        // the glass: the tab icons are carried by WeChat's own translation, and
        // moving the glass alone would slide it out from under them.
        // Only worth computing while the bar is actually moving.
        if (LiquidGlassModule.app() == HostApp.PDD) {
            // Held at zero for PDD while its bar's scroll-linked translations
            // exist (proven by the 1.0.3 field test): the shortfall scaling
            // below exists for WeChat's deliberate full-height hide, and on
            // the 1.0.2 field test the whole pill visibly bobbed over the
            // feed while scrolling. The glass and droplet still mirror the
            // bar's own translation, so a genuine hide still reads; only the
            // host's extra travel is frozen out.
            host.setTranslationY(0f);
        } else {
            host.setTranslationY(ty == 0f ? 0f : hideShortfall(host, tabView) * ty);
        }

        View glass = sGlassRef.get();
        if (glass != null && glass.getTranslationY() != ty) {
            glass.setTranslationY(ty);
        }
        View droplet = sDropletRef.get();
        if (droplet != null) {
            droplet.setTranslationY(sDropletBaseY + ty);
            droplet.setAlpha(gone ? 0f : alpha);
        }
        if (glass != null) {
            glass.setAlpha(gone ? 0f : alpha);
        }
        host.setShadowOffsetY(gone ? Float.MAX_VALUE : ty, alpha);
    }

    /** Puts a layer back to GONE if the app re-showed it; reports once. */
    private static void holdHidden(View v, boolean report) {
        if (v == null || v.getVisibility() == View.GONE) {
            return;
        }
        v.setVisibility(View.GONE);
        if (report && !sBlurRelit) {
            sBlurRelit = true;
            LiquidGlassModule.log(android.util.Log.INFO,
                    "the app re-lit its own blur layer; holding it hidden");
        }
    }

    /**
     * Keeps QQ's docked-bar chrome hidden even when a skin refresh replaces it.
     *
     * <p>Day/night switches can build the replacement blur wrapper as GONE and
     * make it visible only after our install pass. Remembering only the layer
     * that happened to be visible during install therefore loses the new
     * wrapper. Re-scan the small sibling list before every draw so both a
     * re-shown instance and a replacement instance are caught before rendering.
     */
    private static void holdOwnBarChromeHidden(LiquidGlassHostLayout host) {
        android.view.ViewParent rawParent = host.getParent();
        HostApp app = LiquidGlassModule.app();
        if (rawParent instanceof ViewGroup
                && app != null && app.hiddenSiblings.length > 0) {
            ViewGroup parent = (ViewGroup) rawParent;
            View tabView = sTabViewRef.get();
            for (int i = 0; i < parent.getChildCount(); i++) {
                View c = parent.getChildAt(i);
                if (c != tabView && app.isHiddenSibling(c.getClass().getName())) {
                    sBlurLayerRef = new WeakReference<>(c);
                    holdHidden(c, true);
                }
            }
        } else {
            holdHidden(sBlurLayerRef.get(), true);
        }
        holdHidden(sHairlineRef.get(), false);
    }

    /**
     * Tracks the selected tab and the visible pager page once per frame.
     *
     * <p>Both have to be polled rather than hooked: {@code setTo(int)} does not
     * fire on tab taps, and the pager scrolls between fixed child offsets instead
     * of restacking them, so nothing notifies us when the page under the glass
     * changes.
     */
    /**
     * Holds the navigation bar transparent.
     *
     * <p>WeChat's chat screen opens inside LauncherUI and paints the navigation
     * bar opaque on its way in, then leaves it that way — which puts a solid
     * strip back over the content the pill floats above. Nothing tells us when
     * that happens (no Activity change, no relayout), so it is simply checked
     * whenever the bar draws; the read is a field access and the write only
     * happens when WeChat has actually changed it.
     */
    private static WeakReference<View> sNavBgRef = new WeakReference<>(null);
    private static int sNavBgId = -1;

    /** The DecorView's navigation-bar backdrop, looked up by its framework id. */
    private static View navBarBackground(View decor) {
        View v = sNavBgRef.get();
        if (v != null && v.getParent() != null
                && v.getRootView() == decor.getRootView()) {
            return v;
        }
        if (sNavBgId == -1) {
            sNavBgId = decor.getResources().getIdentifier(
                    "navigationBarBackground", "id", "android");
        }
        if (sNavBgId == 0) {
            return null;
        }
        v = decor.findViewById(sNavBgId);
        sNavBgRef = new WeakReference<>(v);
        return v;
    }

    /**
     * Keeps the navigation bar from covering the content.
     *
     * <p>WeChat's chat screen opens inside LauncherUI — no Activity change, no
     * window focus change — and paints the navigation bar opaque on its way in,
     * leaving it that way on the way out. That is the strip that reappears at
     * the bottom: the system bar drawn over content that already reaches the
     * edge, not a layout gap.
     *
     * <p>Setting the colour back is no use here. On HyperOS the value reads
     * straight back as WeChat's, so the write never lands. The bar's backdrop is
     * an ordinary View inside the DecorView though, and hiding that is entirely
     * ours to do — and it holds, because the DecorView has to run a draw pass to
     * show it again, and this runs first in every one of them.
     */
    private static void keepNavBarClear() {
        Activity a = sActivityRef.get();
        if (a == null) {
            return;
        }
        View decor = a.getWindow().getDecorView();
        View navBg = navBarBackground(decor);
        if (navBg != null && navBg.getVisibility() != View.GONE) {
            navBg.setVisibility(View.GONE);
        }
        // Checked separately: losing this flag shrinks the window back above the
        // gesture bar, which is a real gap rather than something drawn over.
        int vis = decor.getSystemUiVisibility();
        if ((vis & View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION) == 0) {
            decor.setSystemUiVisibility(
                    vis | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    /**
     * Cheap fingerprint of the row topology and title layout mode.
     *
     * <p>Measured geometry is deliberately excluded because it changes on every
     * layout. LayoutParams identity and the equal-weight bit are retained: QQ's
     * Material bar can reuse the same TabViews while replacing their content or
     * restoring {@code width=0, weight=1}, and either transition must re-hug the
     * existing row.
     */
    private static int tabStructureSignature(ViewGroup tabRow) {
        if (tabRow == null) {
            return 0;
        }
        int signature = System.identityHashCode(tabRow);
        signature = signature * 31 + tabRow.getVisibility();
        signature = signature * 31 + tabRow.getChildCount();
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            View tab = tabRow.getChildAt(i);
            signature = signature * 31 + System.identityHashCode(tab);
            signature = signature * 31 + tab.getVisibility();
            ViewGroup.LayoutParams lp = tab.getLayoutParams();
            signature = signature * 31 + System.identityHashCode(lp);
            boolean equalWeight = lp != null && lp.width == 0;
            if (lp instanceof android.widget.LinearLayout.LayoutParams) {
                equalWeight |= ((android.widget.LinearLayout.LayoutParams) lp).weight != 0f;
            }
            signature = signature * 31 + (equalWeight ? 1 : 0);
            if (tab instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) tab;
                signature = signature * 31 + group.getChildCount();
                for (int j = 0; j < group.getChildCount(); j++) {
                    View child = group.getChildAt(j);
                    signature = signature * 31 + System.identityHashCode(child);
                    signature = signature * 31 + child.getVisibility();
                }
            }
        }
        return signature;
    }

    /**
     * Watches QQ's runtime-configurable tabs without measuring on every frame.
     *
     * <p>The actual repair is posted out of pre-draw: it changes LayoutParams and
     * must be allowed to trigger a clean layout pass. Returning {@code true}
     * tells the selection watcher to wait until the new row is rebound.
     */
    private static boolean scheduleTabStructureRefreshIfNeeded(
            LiquidGlassHostLayout host) {
        View tabView = sTabViewRef.get();
        if (!(tabView instanceof ViewGroup)) {
            return false;
        }
        ViewGroup current = TabBarBridge.findTabRow((ViewGroup) tabView);
        if (current == null || current.getVisibility() != View.VISIBLE
                || TabBarBridge.tabCount(current) == 0) {
            // QQ briefly detaches/empties Material's indicator while applying
            // the setting. Keep the old binding untouched and retry next frame
            // — but not indefinitely: a row that never comes back would
            // otherwise hold this method's `true` forever, and with it the
            // selection watcher and the droplet.
            ViewGroup bound = sTabRowRef.get();
            boolean boundUsable = bound != null && bound.isAttachedToWindow()
                    && bound.getVisibility() == View.VISIBLE
                    && TabBarBridge.tabCount(bound) > 0;
            if (boundUsable) {
                sTabRowEmptySinceMs = 0L;
                return true;
            }
            long now = android.os.SystemClock.uptimeMillis();
            if (sTabRowEmptySinceMs == 0L) {
                sTabRowEmptySinceMs = now;
            }
            return now - sTabRowEmptySinceMs < TAB_ROW_EMPTY_GIVE_UP_MS;
        }
        sTabRowEmptySinceMs = 0L;
        if (sTabStructureRefreshPosted) {
            return true;
        }
        int signature = tabStructureSignature(current);
        if (current == sTabRowRef.get() && signature == sTabStructureSignature) {
            return false;
        }
        sTabStructureRefreshPosted = true;
        host.post(() -> refreshTabStructure(host));
        return true;
    }

    /** Re-hugs and rebinds a bottom bar after its tab topology changes. */
    private static void refreshTabStructure(LiquidGlassHostLayout host) {
        try {
            if (host != sHostRef.get() || host.getParent() == null) {
                return;
            }
            View tabView = sTabViewRef.get();
            if (!(tabView instanceof ViewGroup)) {
                return;
            }
            ViewGroup tabRow = TabBarBridge.findTabRow((ViewGroup) tabView);
            if (tabRow == null || tabRow.getVisibility() != View.VISIBLE
                    || TabBarBridge.tabCount(tabRow) == 0) {
                return;
            }

            // Newly created Material TabViews bring QQ's own backgrounds and
            // equal-weight widths and docked height back with them. Apply the
            // same treatment as the initial install, then resize the already-live
            // host around the row.
            stripSolidBackgrounds(tabView);
            TabBarBridge.tagTabIcons(tabRow);
            centerPddTabContent(tabRow);
            disableTabWidgetStrips(tabView);
            dropNavReserve(tabView);
            ViewGroup.LayoutParams tabLp = tabView.getLayoutParams();
            if (tabLp != null && sBarHeight > 0 && tabLp.height != sBarHeight) {
                tabLp.height = sBarHeight;
                tabView.setLayoutParams(tabLp);
            }
            float density = host.getResources().getDisplayMetrics().density;
            int barWidth = hugContentWidth(tabRow, density);
            if (barWidth <= 0) {
                return; // not laid out yet; leave the old signature so we retry
            }
            ViewGroup.LayoutParams lp = host.getLayoutParams();
            int desired = barWidth + host.shadowPad() * 2;
            if (host.getParent() instanceof View
                    && ((View) host.getParent()).getWidth() > 0) {
                desired = Math.min(desired,
                        ((View) host.getParent()).getWidth());
            }
            if (lp != null && lp.width != desired) {
                lp.width = desired;
                host.setLayoutParams(lp);
            }

            sTabRowRef = new WeakReference<>(tabRow);
            sTabStructureSignature = tabStructureSignature(tabRow);
            View droplet = sDropletRef.get();
            if (droplet instanceof DropletPanel) {
                ((DropletPanel) droplet).setTabRow(tabRow);
            }
            if (sDrag != null) {
                sDrag.setTabRow(tabRow);
            } else if (droplet instanceof DropletPanel
                    && sGlassRef.get() instanceof LiquidGlassPanel) {
                // Self-heal: a drag controller that never got created at
                // install time is created here instead of staying missing.
                sDrag = new DropletDragController(
                        (DropletPanel) droplet, tabRow, density,
                        isNight(host.getContext()));
                sDrag.setPill((LiquidGlassPanel) sGlassRef.get());
                sDrag.setHost(host);
                host.setDragHandler(sDrag);
                LiquidGlassModule.log(android.util.Log.INFO,
                        "drag controller attached on structure refresh");
            }

            // The next pre-draw runs after the requested layout and snaps the
            // droplet to the selected tab using the new geometry.
            sLastIndex = -1;
            tabRow.requestLayout();
            tabView.requestLayout();
            host.requestLayout();
            LiquidGlassModule.log(android.util.Log.INFO,
                    "tab structure rebound: row=" + tabRow.getClass().getName()
                            + " children=" + tabRow.getChildCount()
                            + " hostWidth=" + (lp == null ? 0 : lp.width)
                            + " barHeight=" + sBarHeight);
        } catch (Throwable t) {
            LiquidGlassModule.logErr("tab structure refresh failed", t);
        } finally {
            sTabStructureRefreshPosted = false;
        }
    }

    /**
     * QQ's skin refresh restores the docked bar's navigation padding without
     * rebuilding the tab row. Topology fingerprints deliberately ignore
     * geometry, so hold this small invariant separately on the pre-draw path.
     */
    private static boolean restoreBarContentHeight(LiquidGlassHostLayout host) {
        View tabView = sTabViewRef.get();
        if (tabView == null) {
            return false;
        }
        boolean changed = dropNavReserve(tabView) > 0;
        ViewGroup.LayoutParams lp = tabView.getLayoutParams();
        if (lp != null && sBarHeight > 0 && lp.height != sBarHeight) {
            lp.height = sBarHeight;
            tabView.setLayoutParams(lp);
            changed = true;
        }
        if (changed) {
            tabView.requestLayout();
            host.requestLayout();
            LiquidGlassModule.log(android.util.Log.INFO,
                    "bar content height restored after host relayout: "
                            + sBarHeight);
        }
        return changed;
    }

    private static void installSelectionWatcher(LiquidGlassHostLayout host) {
        host.getViewTreeObserver().addOnPreDrawListener(() -> {
            if (host != sHostRef.get() || !host.isAttachedToWindow()) {
                return true;
            }
            // First, and on its own: anything below must not be able to stop the
            // navigation bar being held clear.
            try {
                keepNavBarClear();
                dropBottomFrostAll();
                holdOwnBarChromeHidden(host);
            } catch (Throwable t) {
                // Once only: this sits on a per-frame path.
                if (!sKeepFailed) {
                    sKeepFailed = true;
                    LiquidGlassModule.logErr("keepNavBarClear failed", t);
                }
            }
            try {
                if (restoreBarContentHeight(host)) {
                    return true;
                }
                followBarOffset(host);
                if (scheduleTabStructureRefreshIfNeeded(host)) {
                    return true;
                }
                // Kept honest per frame: both apps undo parts of the page
                // surgery on layout passes of their own, and a scroller that
                // has gone back to clipping its padding paints an opaque band
                // over the content it is scrolling.
                maintainScrollerPadding();
                // PDD's floating back-to-top button sits where the pill floats;
                // lift it above the pill so it stays visible and tappable.
                liftBackToTopButton(host);
                // The feed RecyclerView may be created after install (PDD
                // loads it asynchronously), so re-run the whole PDD expand
                // (stretch + pad) every 5th frame (~80ms) until it is
                // found, and so PDD cannot put the short pages back on a
                // layout pass either. After the first successful pass the
                // setters are no-ops. A newly switched-to page is stretched
                // within ~80ms of appearing.
                if (LiquidGlassModule.app() == HostApp.PDD) {
                    if (++sPddExpandFrame % 5 == 0) {
                        ViewGroup pgr = sPagerRef.get();
                        if (pgr != null) {
                            extendPagesToBottom(pgr);
                        }
                    }
                }
                sPageExtent.request();
                maintainDropletGeometry();
                ViewGroup tabRow = sTabRowRef.get();
                int sel = visibleTabSelection(tabRow);
                if (sel >= 0 && sel != sLastIndex) {
                    boolean first = sLastIndex < 0;
                    sLastIndex = sel;
                    syncDropletSize(sel);
                    if (sDrag != null) {
                        // KernelSU animates programmatic switches the same way as
                        // drags: press, travel, release.
                        sDrag.animateToIndex(sel, first);
                    }
                    ViewGroup pgr = sPagerRef.get();
                    if (pgr != null) {
                        pgr.post(() -> extendPagesToBottom(pgr));
                    }
                }
            } catch (Throwable ignored) {
            }
            return true;
        });
    }

    /**
     * How long a page/bar disagreement has to last before the page is believed:
     * long enough that the ordinary lag between a page scroll and the bar's own
     * selection never reaches it.
     */
    private static final long PAGE_BAR_SETTLE_MS = 250L;
    /** Uptime at which the current disagreement began, or 0 while they agree. */
    private static long sPageBarDisagreeSinceMs;
    private static boolean sPageWonLogged;
    /** Draws the frame that settles a disagreement, in case nothing else would. */
    private static final Runnable sPageBarSettle = () -> {
        LiquidGlassHostLayout host = sHostRef.get();
        if (host != null && sPageBarDisagreeSinceMs != 0L) {
            host.invalidate();
        }
    };

    /**
     * Selected slot, with the page actually on screen given the last word.
     *
     * <p>Both apps occasionally leave the bar behind the pager. The reported
     * case is WeChat's wallet: coming back from it the pager is on the chat
     * list while the tab views still hold 我, so a droplet that only ever reads
     * the bar highlights a page nobody is looking at (#9). The page wins, but
     * only once the disagreement has held for a while, so a bar that is merely
     * a step behind still drives the animation.
     *
     * <p>Timed on the clock, not counted in frames: frames stop coming once the
     * screen settles, and the wallet case settles right there.
     */
    private static int visibleTabSelection(ViewGroup tabRow) {
        int selected = TabBarBridge.selectedIndex(tabRow);
        int page = TabBarBridge.pageSlot(sPagerRef.get(), tabRow);
        LiquidGlassHostLayout host = sHostRef.get();
        if (page < 0 || page == selected) {
            if (sPageBarDisagreeSinceMs != 0L && host != null) {
                host.removeCallbacks(sPageBarSettle);
            }
            sPageBarDisagreeSinceMs = 0L;
            return selected;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (sPageBarDisagreeSinceMs == 0L) {
            sPageBarDisagreeSinceMs = now;
            if (host != null) {
                host.postDelayed(sPageBarSettle, PAGE_BAR_SETTLE_MS + 16L);
            }
            return selected;
        }
        if (now - sPageBarDisagreeSinceMs < PAGE_BAR_SETTLE_MS) {
            return selected;
        }
        if (!sPageWonLogged) {
            sPageWonLogged = true;
            LiquidGlassModule.log(android.util.Log.INFO,
                    "bar still reports tab " + selected + " while page " + page
                            + " is on screen; following the page");
        }
        return page;
    }

    /**
     * Re-runs the page stretch on a slow timer, asked for from every frame.
     *
     * <p>The apps re-apply their own page metrics whenever they relayout, and
     * they do it silently — no layout change on any view we listen to. When
     * that happens the pages stop short again and the band the bar vacated
     * shows the window's own background: the white strip under the pill, and
     * the grey-and-white patchwork at the end of a scrolled list (#6).
     *
     * <p>Cheap by construction: every step inside {@link #extendPagesToBottom}
     * bails out on its own when its gap is already closed, so a run costs a
     * short walk of the pages down to their scrollers — until something really
     * did move, and then it is repaired within half a second instead of at the
     * next tab switch. The last run lands after the frames stop, which is
     * exactly when a relayout that put the gap back has just finished drawing.
     */
    private static final ClockThrottle sPageExtent = new ClockThrottle(500L, () -> {
        ViewGroup pager = sPagerRef.get();
        if (pager != null && pager.isAttachedToWindow()) {
            extendPagesToBottom(pager);
        }
    });

    /**
     * The sibling the glass refracts. WeChat puts the pager and the tab bar under
     * the same parent, so the backdrop is whichever sibling is not the bar itself
     * and actually covers the screen.
     */
    private static ViewGroup findBackdrop(ViewGroup parent, View tabView) {
        ViewGroup best = null;
        int bestArea = 0;
        HostApp app = LiquidGlassModule.app();
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c == tabView || !(c instanceof ViewGroup)
                    || c.getVisibility() != View.VISIBLE
                    || (app != null && app.isHiddenSibling(c.getClass().getName()))) {
                continue;
            }
            int area = c.getWidth() * c.getHeight();
            if (area > bestArea) {
                bestArea = area;
                best = (ViewGroup) c;
            }
        }
        return best;
    }

    /**
     * WeChat paints the bar's opaque colour on the inner LinearLayout
     * ({@code E.setBackgroundColor(...)}). Only flat colour fills are removed —
     * anything else (badge shapes, ripples) is left alone.
     */
    private static void stripSolidBackgrounds(View v) {
        // The bar and its row are drawn by the glass now, so clear their
        // backgrounds outright rather than only flat fills: WeChat paints the
        // bar's hairline top divider through a non-ColorDrawable background, and
        // leaving it in draws a stray line across the top of the pill that stops
        // short at the row's padding edge.
        if (v.getBackground() != null) {
            v.setBackground(null);
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            // Down to the tab columns themselves: each carries a top hairline,
            // and the four of them line up into a divider spanning the row's
            // content box (214..1006 px) right across the pill's top edge.
            // Their children keep theirs (badges, icon states).
            for (int i = 0; i < vg.getChildCount(); i++) {
                View c = vg.getChildAt(i);
                if (c.getBackground() != null) {
                    c.setBackground(null);
                }
                if (c instanceof ViewGroup) {
                    ViewGroup row = (ViewGroup) c;
                    for (int j = 0; j < row.getChildCount(); j++) {
                        View tab = row.getChildAt(j);
                        if (tab.getBackground() != null) {
                            tab.setBackground(null);
                        }
                    }
                }
            }
        }
    }

    /**
     * Hides the app's own frosted layer behind the bar.
     *
     * <p>QQ ships one: a 54dp {@code QQBlurViewWrapper} pinned to the bottom of
     * the same FrameLayout, drawn between the page and the tab bar. It is the
     * app's answer to the same problem this module solves, and the two do not
     * compose — left visible it covers the page the glass is meant to refract,
     * so the pill would show a blur of a blur. WeChat has no equivalent and the
     * list is simply empty there.
     */
    private static void hideOwnBlurLayers(ViewGroup parent, View tabView) {
        HostApp app = LiquidGlassModule.app();
        if (app == null || app.hiddenSiblings.length == 0) {
            return;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c != tabView && app.isHiddenSibling(c.getClass().getName())) {
                sBlurLayerRef = new WeakReference<>(c);
                if (c.getVisibility() != View.GONE) {
                    c.setVisibility(View.GONE);
                    LiquidGlassModule.log(android.util.Log.INFO,
                            "hid the app's own blur layer: "
                                    + c.getClass().getName());
                }
            }
        }
    }

    /**
     * Puts the app's blur layer back, for the path where the pill never
     * arrived. Only ever undoes {@link #hideOwnBlurLayers}, which is why the
     * restored state is unconditionally VISIBLE: nothing else is touched.
     */
    private static void showOwnBlurLayers(ViewGroup parent) {
        HostApp app = LiquidGlassModule.app();
        if (app == null || app.hiddenSiblings.length == 0) {
            return;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c.getVisibility() == View.GONE
                    && app.isHiddenSibling(c.getClass().getName())) {
                c.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * Hides the hairline the app rules across the top of its docked bar.
     *
     * <p>QQ draws it as a sibling of the bar — a 1px full-width View with a flat
     * colour — so clearing backgrounds inside the bar never reaches it, and once
     * the bar floats away it is left behind as a line straight across the page.
     * Matched on shape, not class: it is a bare {@code android.view.View}, a name
     * far too common to go by. One pixel tall and nearly the full width is not
     * something else in this layout.
     */
    private static void hideBarHairline(ViewGroup parent, View tabView) {
        float density = parent.getResources().getDisplayMetrics().density;
        int maxThickness = Math.max(2, Math.round(density * 1.5f));
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c == tabView || c.getVisibility() != View.VISIBLE
                    || c instanceof ViewGroup) {
                continue;
            }
            if (c.getHeight() > 0 && c.getHeight() <= maxThickness
                    && c.getWidth() >= parent.getWidth() * 0.9f
                    && c.getBackground() != null) {
                // 2026-11-05 home-back fix: PDD's exit branch needs this
                // divider present at back-press time, and the per-frame
                // holdHidden(sHairlineRef) would re-hide any tracked
                // divider — so for PDD the divider is made fully
                // transparent instead of GONE, and deliberately NOT
                // tracked, leaving no stray line and a working back key.
                if (LiquidGlassModule.app() == HostApp.PDD) {
                    c.setAlpha(0f);
                    LiquidGlassModule.log(android.util.Log.INFO,
                            "hairline transparent, " + c.getHeight() + "px");
                } else {
                    c.setVisibility(View.GONE);
                    sHairlineRef = new WeakReference<>(c);
                    LiquidGlassModule.log(android.util.Log.INFO,
                            "hid the bar's " + c.getHeight() + "px hairline");
                }
            }
        }
    }

    /**
     * Turns off the divider strips {@code android.widget.TabWidget} draws itself.
     *
     * <p>QQ's bar is a TabWidget subclass, and the strips are painted in
     * {@code dispatchDraw} rather than through the background — clearing the
     * background does not touch them, and they would run right across the pill.
     */
    private static void disableTabWidgetStrips(View tabView) {
        if (tabView instanceof android.widget.TabWidget) {
            android.widget.TabWidget tw = (android.widget.TabWidget) tabView;
            tw.setStripEnabled(false);
            tw.setDividerDrawable(null);
        }
    }

    /**
     * Drops the room the bar holds at its bottom for the navigation bar.
     *
     * <p>A docked bar has to keep its tabs clear of the gesture pill, and QQ
     * does it with bottom padding — 60px here, exactly the navigation inset.
     * Floating puts the pill above that area to begin with, so the reserve
     * survives only as a band of empty glass under the tabs, as tall as the
     * inset. It has to go before the bar's height is read, since that height
     * is what the pill is built to.
     *
     * <p>WeChat reserves nothing (its bar measures 162px with no padding at
     * all), so this is a no-op there.
     *
     * @return the padding removed, which the caller owes the pill's height
     */
    /**
     * Bar height that leaves the same gap above and below the tabs' content.
     *
     * <p>The bar's own height is not trustworthy under edge-to-edge. On some
     * devices 8.0.72 sizes the bar to cover the navigation inset without putting
     * it anywhere {@link #dropNavReserve} can find it, so the bar measures some
     * 20dp taller than what it actually draws. The pill inherits that as a band
     * of dead space along its bottom edge: the tabs sit against the top with a
     * gap underneath — measured at +72px and +53px on the two devices in
     * issue #1, against −3px on a third whose reserve is a single pixel and
     * which therefore cannot reproduce it at all.
     *
     * <p>So it is measured off the content instead. Whatever gap the bar leaves
     * above its icons is the one it means to have; mirroring that below gives
     * the height the bar would have had without the inset, no matter which route
     * the inset took to get in.
     *
     * <p>Only ever shrinks the bar. If the content cannot be located, or the
     * symmetric height would be taller than what the bar reports, the bar's own
     * height stands.
     */
    private static int contentBarHeight(ViewGroup tabRow, int fallback) {
        if (tabRow == null || fallback <= 0) {
            return fallback;
        }
        int top = Integer.MAX_VALUE;
        int bottom = 0;
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            View tab = tabRow.getChildAt(i);
            if (tab.getVisibility() != View.VISIBLE) {
                continue;
            }
            int[] b = {Integer.MAX_VALUE, 0};
            leafBounds(tab, 0, b);
            if (b[0] < b[1]) {
                int base = tabRow.getTop() + tab.getTop();
                top = Math.min(top, base + b[0]);
                bottom = Math.max(bottom, base + b[1]);
            }
        }
        if (top == Integer.MAX_VALUE || bottom <= top) {
            return fallback;
        }
        int symmetric = bottom + top;
        LiquidGlassModule.log(android.util.Log.INFO,
                "bar height: content " + top + ".." + bottom
                        + " -> " + symmetric + " (bar reports " + fallback + ")");
        return symmetric > 0 && symmetric < fallback ? symmetric : fallback;
    }

    /**
     * PDD's bar height, measured off the tab content alone.
     *
     * <p>The generic {@link #contentBarHeight} mirrors the top gap below the
     * content, which is right when the gap is inside the bar — but for PDD
     * the row is the bar, so that "gap" is the bar's own position in its
     * parent and the mirror lands far above the bar's real height. The
     * result was the full 193px pill: a 47px empty band on top, 146px tabs
     * at the bottom, and the droplet sitting low with twice as much glass
     * above it as below.
     *
     * <p>Here the height is the content span plus a 4dp rim each side, never
     * less than the tab column itself so a fixed-height tab is never
     * squeezed. The bar's own top padding — the dead strip's other half —
     * is dropped by the horizontal hug below, which already zeroes vertical
     * padding on the row.
     */
    private static int pddBarHeight(ViewGroup tabRow, int fallback, float density) {
        if (tabRow == null || fallback <= 0) {
            return fallback;
        }
        int top = Integer.MAX_VALUE;
        int bottom = 0;
        int tabHeight = 0;
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            View tab = tabRow.getChildAt(i);
            if (tab.getVisibility() != View.VISIBLE) {
                continue;
            }
            tabHeight = Math.max(tabHeight, tab.getHeight());
            int[] b = {Integer.MAX_VALUE, 0};
            leafBounds(tab, 0, b);
            if (b[0] < b[1]) {
                top = Math.min(top, b[0]);
                bottom = Math.max(bottom, b[1]);
            }
        }
        if (top == Integer.MAX_VALUE || bottom <= top) {
            return fallback;
        }
        int inset = Math.round(density * 4f);
        int extra = Math.round(density * GlassConfig.pillExtraHeightDp);
        int desired = Math.max(tabHeight, (bottom - top) + inset * 2) + extra;
        LiquidGlassModule.log(android.util.Log.INFO,
                "pdd bar height: content " + top + ".." + bottom
                        + " tabH=" + tabHeight + " extra=" + extra
                        + " -> " + desired
                        + " (bar reports " + fallback + ")");
        return desired > 0 ? desired : fallback;
    }

    /**
     * Centres each tab's icon-and-label stack inside its own column.
     *
     * <p>Centring the tab <em>column</em> in the pill is not enough: PDD's
     * content sits low inside the column itself (the stack's centre is
     * below the column's centre), so even a perfectly centred column reads
     * as icons riding low in the glass. The shift is measured per tab off
     * the visible content leaves and applied as a translation to the tab's
     * own children, so the real tab and the droplet's redrawn copy of it
     * move together, while the tab box (and therefore the droplet's
     * position) does not move at all.
     *
     * <p>1.0.8's version of this measured with the generic leaf bounds,
     * which count a full-height layer — the tab's click consumer, which
     * spans the whole column — as content. That layer alone spans
     * 0..tabHeight, so the measurement always came back "already centred"
     * and nothing moved. Here full-height leaves are excluded from the
     * measurement, and only the click consumer itself (resolved through
     * its field) is excluded from the translation, so a full-height
     * content container still moves with its icon and label.
     */
    private static void centerPddTabContent(ViewGroup tabRow) {
        if (LiquidGlassModule.app() != HostApp.PDD || tabRow == null) {
            return;
        }
        try {
            for (int i = 0; i < tabRow.getChildCount(); i++) {
                View tab = tabRow.getChildAt(i);
                if (!(tab instanceof ViewGroup)
                        || tab.getVisibility() != View.VISIBLE
                        || tab.getHeight() <= 0) {
                    continue;
                }
                int[] b = {Integer.MAX_VALUE, 0};
                tabContentBounds(tab, tab.getHeight(), 0, b);
                if (b[0] >= b[1]) {
                    continue;
                }
                float shift = (b[0] + b[1]) * 0.5f - tab.getHeight() * 0.5f;
                // On top of centring, a further small lift: the field
                // feedback after centring was still "a bit higher". 6dp is
                // enough to read on a screenshot without pushing the stack
                // into the tab's top edge — clamped per tab to the centred
                // top margin, so a full-height promo graphic (whose centred
                // top margin is zero) is not clipped by it.
                float centredTop = b[0] - shift;
                float extraUp = Math.min(
                        tab.getResources().getDisplayMetrics().density * 6f,
                        Math.max(0f, centredTop));
                float total = -(shift + extraUp);
                View click = fieldView(tab, "mClickConsumeView");
                ViewGroup g = (ViewGroup) tab;
                for (int j = 0; j < g.getChildCount(); j++) {
                    View c = g.getChildAt(j);
                    if (c != click && c.getVisibility() == View.VISIBLE
                            && c.getHeight() > 0) {
                        c.setTranslationY(total);
                    }
                }
                LiquidGlassModule.log(android.util.Log.INFO,
                        "pdd tab content centred: tab=" + i
                                + " content=" + b[0] + ".." + b[1]
                                + " tabH=" + tab.getHeight()
                                + " shift=" + total
                                + " extraUp=" + extraUp);
            }
        } catch (Throwable t) {
            LiquidGlassModule.logErr("could not centre tab content", t);
        }
    }

    /**
     * Vertical span of a tab's real content, relative to the tab's top.
     * Like {@link #leafBounds} but ignoring full-height leaves: a layer
     * spanning the whole column (background, click consumer) is not
     * content, and letting it into the span centres nothing.
     */
    private static void tabContentBounds(View v, int tabHeight, int offset,
                                         int[] out) {
        if (v.getVisibility() != View.VISIBLE) {
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                tabContentBounds(c, tabHeight, offset + c.getTop(), out);
            }
            return;
        }
        if (v.getWidth() <= 0 || v.getHeight() <= 0
                || v.getHeight() >= tabHeight) {
            return;
        }
        out[0] = Math.min(out[0], offset);
        out[1] = Math.max(out[1], offset + v.getHeight());
    }

    /** A view a tab item exposes through a named field, or null. */
    private static View fieldView(View tab, String fieldName) {
        if (tab == null) {
            return null;
        }
        for (Class<?> k = tab.getClass(); k != null && k != Object.class;
                k = k.getSuperclass()) {
            try {
                java.lang.reflect.Field f = k.getDeclaredField(fieldName);
                f.setAccessible(true);
                Object v = f.get(tab);
                return v instanceof View ? (View) v : null;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** Vertical extent of a tab's drawn content, relative to the tab column. */
    private static void leafBounds(View v, int offset, int[] out) {
        if (v.getVisibility() != View.VISIBLE) {
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                leafBounds(c, offset + c.getTop(), out);
            }
            return;
        }
        if (v.getWidth() <= 0 || v.getHeight() <= 0) {
            return;
        }
        out[0] = Math.min(out[0], offset);
        out[1] = Math.max(out[1], offset + v.getHeight());
    }

    private static int dropNavReserve(View tabView) {
        int reserve = tabView.getPaddingBottom();
        if (reserve <= 0) {
            return 0;
        }
        tabView.setPadding(tabView.getPaddingLeft(), tabView.getPaddingTop(),
                tabView.getPaddingRight(), 0);
        LiquidGlassModule.log(android.util.Log.INFO,
                "dropped the bar's " + reserve + "px navigation reserve");
        return reserve;
    }

    private static final int EDGE_TAG_KEY = 0x7F5A0002;

    /**
     * Lets the pages draw under the gesture bar.
     *
     * <p>WeChat's window stops at the navigation bar, so once the tab bar is
     * lifted off the bottom there is a dead strip below it that nothing ever
     * paints. Asking for the hide-navigation layout grows the window to the
     * whole screen; the navigation inset is then stripped on its way down the
     * tree, because otherwise WeChat's own containers just pad the same gap
     * straight back in.
     *
     * @return whether the window was actually grown
     */
    private static boolean extendUnderNavBar(Context ctx) {
        if (Build.VERSION.SDK_INT < 30) {
            return false;
        }
        if (LiquidGlassModule.app() == HostApp.PDD) {
            // PDD sizes its frame to end at the navigation bar and manages
            // its own insets (its content area is the window minus the nav
            // strip, exactly). Stripping the inset from its tree would move
            // its hybrid pages; the pill anchors to the frame's own bottom
            // instead, where the stock bar sat.
            return false;
        }
        try {
            Activity activity = activityOf(ctx);
            if (activity == null) {
                activity = sActivityRef.get();
            }
            if (activity == null) {
                LiquidGlassModule.log(android.util.Log.WARN,
                        "no Activity for the window, bottom strip stays blank");
                return false;
            }
            android.view.Window w = activity.getWindow();
            View decor = w.getDecorView();
            boolean first = !Boolean.TRUE.equals(decor.getTag(EDGE_TAG_KEY));

            w.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            w.setNavigationBarContrastEnforced(false);
            android.view.WindowInsetsController ctrl = w.getInsetsController();
            if (ctrl != null) {
                // The gesture pill now sits on WeChat's own content rather than
                // on a system background, so it has to contrast with that.
                int light = android.view.WindowInsetsController
                        .APPEARANCE_LIGHT_NAVIGATION_BARS;
                ctrl.setSystemBarsAppearance(isNight(ctx) ? 0 : light, light);
            }
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            if (first) {
                decor.setTag(EDGE_TAG_KEY, Boolean.TRUE);
                decor.setOnApplyWindowInsetsListener((v, insets) -> {
                    int inset = rememberNavigationInset(insets);
                    LiquidGlassHostLayout host = sHostRef.get();
                    if (host != null
                            && host.getRootView() == v.getRootView()) {
                        syncHostBottomInset(host, inset);
                    }
                    WindowInsets stripped = new WindowInsets.Builder(insets)
                            .setInsets(WindowInsets.Type.navigationBars(),
                                    android.graphics.Insets.NONE)
                            .build();
                    return v.onApplyWindowInsets(stripped);
                });
                LiquidGlassModule.log(android.util.Log.INFO,
                        "window extended under the navigation bar, inset="
                                + navInset(decor));
            }
            decor.requestApplyInsets();
            return true;
        } catch (Throwable t) {
            LiquidGlassModule.logErr("could not extend under the nav bar", t);
            return false;
        }
    }

    /**
     * Keeps the bottom strip filled without guessing at timings.
     *
     * <p>Coming back from a chat re-runs WeChat's own window setup, and it does
     * so on its own schedule — a delayed one-shot after resume sometimes lands
     * before WeChat has finished handing the navigation inset back. Watching the
     * pager's layout and the window's focus catches it whenever it happens.
     */
    private static void watchBottomInset(LiquidGlassHostLayout host, ViewGroup backdrop) {
        backdrop.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> {
            extendPagesToBottom(backdrop);
            // Catches the transition frame itself rather than waiting for the
            // bar's next draw, so the strip never flashes on the way back.
            keepNavBarClear();
        });
        host.getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> {
            if (!hasFocus) {
                return;
            }
            Activity a = sActivityRef.get();
            if (a != null) {
                reassertBottom(a);
            }
        });
    }

    /**
     * Re-applies the edge-to-edge window state and re-stretches the pages.
     *
     * <p>Runs on every resume of an already-installed window. WeChat restores
     * its own system-ui flags when a secondary screen closes, which hands the
     * navigation inset straight back to its containers.
     */
    private static void reassertBottom(Activity activity) {
        if (!extendUnderNavBar(activity)) {
            return;
        }
        ViewGroup pager = sPagerRef.get();
        if (pager == null) {
            return;
        }
        pager.post(() -> extendPagesToBottom(pager));
        // The insets land a frame or two after the window is re-shown, and the
        // pages can only be stretched once the room is actually there.
        pager.postDelayed(() -> extendPagesToBottom(pager), 400L);
    }



    /**
     * Stops WeChat repainting the navigation bar over the content.
     *
     * <p>The chat screen opens inside LauncherUI — no Activity change, no window
     * focus change — and on its way in it paints the navigation bar opaque,
     * leaving it that way on the way out. That is the strip that reappears at
     * the bottom: not a layout gap, the system bar itself drawn over content
     * that already reaches the edge.
     *
     * <p>Correcting it after the fact is not enough. There is nothing to react
     * to, and a per-frame check only runs while something is being drawn — once
     * the list settles, no frames, no correction. So the call itself is
     * swallowed for this one window instead.
     */

    private static Activity activityOf(Context ctx) {
        while (ctx instanceof android.content.ContextWrapper) {
            if (ctx instanceof Activity) {
                return (Activity) ctx;
            }
            ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }

    private static int navInset(View anchor) {
        try {
            WindowInsets insets = anchor.getRootWindowInsets();
            return insets == null ? 0 : navigationInset(insets);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Returns the last non-zero system inset once our listener has stripped it. */
    private static int rememberNavigationInset(View anchor) {
        int inset = navInset(anchor);
        if (inset > 0) {
            sNavigationInset = inset;
        }
        return inset > 0 ? inset : sNavigationInset;
    }

    private static int rememberNavigationInset(WindowInsets insets) {
        int inset = navigationInset(insets);
        // Theme recreation sends a transient zero-inset dispatch before the
        // real navigation-bar frame. Do not let that one frame pull an already
        // anchored pill down; the following non-zero dispatch refreshes the
        // cache normally. A process that genuinely starts at zero still keeps
        // zero until it sees a real bar.
        if (inset > 0 || sNavigationInset == 0) {
            sNavigationInset = inset;
        }
        return inset > 0 ? inset : sNavigationInset;
    }

    private static int navigationInset(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            return insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.navigationBars()).bottom;
        }
        return insets.getSystemWindowInsetBottom();
    }

    /** Keeps an already-installed pill anchored when system insets change. */
    private static void syncHostBottomInset(LiquidGlassHostLayout host, int inset) {
        ViewGroup.LayoutParams raw = host.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) {
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        float density = host.getResources().getDisplayMetrics().density;
        int desired = Math.round(density * GlassConfig.barOffsetDp)
                - host.shadowPad() + Math.max(0, inset);
        if (lp.bottomMargin == desired) {
            return;
        }
        lp.bottomMargin = desired;
        host.setLayoutParams(lp);
        LiquidGlassModule.log(android.util.Log.INFO,
                "pill bottom anchor refreshed: inset=" + inset
                        + " margin=" + desired);
    }

    /* ---------------- renderer ---------------- */

    private static boolean isNight(Context ctx) {
        return (ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private static void attachRenderer(Context ctx, LiquidGlassHostLayout host,
                                       ViewGroup backdrop, float density,
                                       ViewGroup tabRowArg) {
        // RenderNode capture and RenderEffect blur are Android 12; below that
        // the host's own software frost is all there is.
        if (Build.VERSION.SDK_INT < LiquidGlassPanel.MIN_SDK) {
            LiquidGlassModule.log(android.util.Log.INFO,
                    "SDK < " + LiquidGlassPanel.MIN_SDK
                            + ", staying on the legacy frost path");
            return;
        }
        try {
            boolean night = isNight(ctx);

            // The glass surface is a direct port of KernelSU's effect stack,
            // see LiquidGlassPanel.
            final LiquidGlassPanel glass =
                    new LiquidGlassPanel(ctx, backdrop, density, night);
            host.addView(glass, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            // The droplet goes on top of the tabs, not under them: it refracts a
            // separately drawn, enlarged copy of the tab row, and that refracted
            // copy is what should be visible inside it — same as KernelSU.
            // The row arrives as an argument, not through the weak ref: on the
            // first PDD field test the ref read back null here for no reason
            // the code could explain, and the drag controller silently never
            // existed. A local the caller is holding cannot do that.
            final DropletPanel droplet = new DropletPanel(
                    ctx, backdrop,
                    tabRowArg != null ? tabRowArg : sTabRowRef.get(),
                    density, night);
            droplet.setVisibility(View.INVISIBLE);
            host.addView(droplet, new FrameLayout.LayoutParams(0, 0,
                    android.view.Gravity.TOP | android.view.Gravity.START));
            droplet.setPill(glass);
            sDropletRef = new WeakReference<>(droplet);
            // The droplet scales past the pill's bounds while held (78/56). Both
            // flags matter: FrameLayout defaults clipToPadding to true, and the
            // host carries 14dp of shadow padding — that alone was shearing off
            // exactly the overflow we wanted to show.
            host.setClipChildren(false);
            host.setClipToPadding(false);
            sGlassRef = new WeakReference<>(glass);

            host.setGlassTuner(new LiquidGlassHostLayout.GlassTuner() {
                @Override
                public void onSize(int w, int h, float cornerRadius) {
                    glass.invalidate();
                }

                @Override
                public void onTheme(boolean dark) {
                    glass.setTheme(dark);
                    droplet.setTheme(dark);
                }
            });

            ViewGroup tabRow = tabRowArg != null ? tabRowArg : sTabRowRef.get();
            if (tabRow != null) {
                sDrag = new DropletDragController(droplet, tabRow, density, night);
                sDrag.setPill(glass);
                sDrag.setHost(host);
                host.setDragHandler(sDrag);
            }

            LiquidGlassModule.log(android.util.Log.INFO,
                    "renderer=KernelSU-style glass (saturation+blur"
                            + (glass.hasLens() ? "+SDF refraction)" : ", no lens)")
                            + " drag=" + (tabRow != null));
        } catch (Throwable t) {
            LiquidGlassModule.logErr("glass renderer unavailable", t);
        }
    }


    /* ---------------- droplet ---------------- */

    /** Resolves hook/getter indices to the row's current visible layout slot. */
    private static int resolveTabSlot(int appIndex) {
        ViewGroup tabRow = sTabRowRef.get();
        int selected = TabBarBridge.selectedIndex(tabRow);
        return selected >= 0 ? selected : TabBarBridge.slotForIndex(tabRow, appIndex);
    }

    /**
     * Called from the tab-switch hook on every in-app page switch — WeChat's
     * {@code setTo(int)}, QQ's {@code setCurrentTab(int)}.
     *
     * <p>This doubles as the install trigger: both apps call it once during
     * startup to select the initial tab, and by then the bar is guaranteed to
     * exist — which the decor-view polling cannot guarantee.
     */
    static void onTabChanged(View tabView, int index) {
        LiquidGlassHostLayout host = sHostRef.get();
        if (host != null && !host.isAttachedToWindow()) {
            resetState();
            host = null;
        }
        if (host == null || host.getParent() == null || tabView.getParent() != host) {
            // Not ours (yet). Either the first call of this process, or a fresh
            // LauncherUI instance after the old one was destroyed.
            if (tabView instanceof ViewGroup && tabView.getParent() != null) {
                tabView.post(() -> {
                    try {
                        install((ViewGroup) tabView);
                        syncDropletSize(resolveTabSlot(index));
                    } catch (Throwable t) {
                        LiquidGlassModule.logErr("install from setTo failed", t);
                    }
                });
            }
            return;
        }
        LiquidGlassHostLayout installedHost = host;
        installedHost.post(() -> {
            if (scheduleTabStructureRefreshIfNeeded(installedHost)) {
                return;
            }
            int slot = resolveTabSlot(index);
            if (slot < 0) {
                return;
            }
            syncDropletSize(slot);
            if (sDrag != null) {
                sDrag.animateToIndex(slot, false);
            }
        });
    }

    /**
     * Sizes and vertically places the droplet for the given tab. Horizontal
     * position and all motion belong to {@link DropletDragController}'s springs.
     *
     * <p>Everything is measured against the host, whose children start at its
     * shadow padding. Reading one level of {@code getTop()} only works while
     * the tab row sits <em>inside</em> the bar; QQ's row <em>is</em> the bar, so
     * the same expression counted that padding twice and dropped the droplet
     * 14dp below — and, through the drag controller's twin expression, 14dp
     * right of — the tab it belongs to, visibly outside the pill. Walking the
     * parents and subtracting the padding is correct for both shapes.
     */
    private static void syncDropletSize(int index) {
        try {
            View droplet = sDropletRef.get();
            ViewGroup tabRow = sTabRowRef.get();
            LiquidGlassHostLayout host = sHostRef.get();
            if (droplet == null || tabRow == null || host == null || index < 0) {
                return;
            }
            View tab = TabBarBridge.tabAt(tabRow, index);
            if (tab == null || tab.getWidth() == 0) {
                return;
            }
            // The pill's inner box. Everything outside it is our own shadow
            // padding, which the droplet must neither include nor overhang.
            int pillTop = host.getPaddingTop();
            int pillBottom = host.getHeight() - host.getPaddingBottom();
            if (pillBottom <= pillTop || !ViewGeom.positionIn(tab, host, sPos)) {
                return;
            }
            float density = host.getResources().getDisplayMetrics().density;
            // The droplet is the selection pill *inside* the pill, so it is
            // sized to the pill, not to the tab column: full tab width, and
            // the pill's inner height less a 4dp rim each side. Sizing it to
            // the tab (146px tall in a 190px pill) left it a short slab
            // floating low inside a taller pill, with a band of empty glass
            // above and below — the size mismatch in the field screenshots.
            // Centred on the pill rather than on the tab: the tabs are
            // centred in the pill too, so the two centres coincide, but the
            // droplet no longer depends on where a tab happens to sit.
            int inset = Math.round(density * 4f);
            int w = tab.getWidth();
            int pillInner = pillBottom - pillTop;
            int h = pillInner - inset * 2;
            if (w <= 0 || h <= 0) {
                return;
            }
            int top = pillTop + inset;
            if (top + h > pillBottom) {
                top = pillBottom - h;
            }
            if (top < pillTop) {
                top = pillTop;
            }
            ViewGroup.LayoutParams lp = droplet.getLayoutParams();
            if (lp.width != w || lp.height != h) {
                lp.width = w;
                lp.height = h;
                droplet.setLayoutParams(lp);
            }
            sDropletBaseY = top - pillTop;
            droplet.setTranslationY(sDropletBaseY);
            droplet.setVisibility(View.VISIBLE);
            sDropletGeometryKey = dropletGeometryKey(host, tab);
            if (!sDropletLogged) {
                sDropletLogged = true;
                LiquidGlassModule.log(android.util.Log.INFO,
                        "droplet placed: tab=" + sPos[0] + "," + sPos[1]
                                + " size=" + w + "x" + h
                                + " pill=" + pillTop + ".." + pillBottom
                                + " pillInner=" + pillInner
                                + " hostPad=" + host.getPaddingLeft()
                                + "," + host.getPaddingTop()
                                + " tabH=" + tab.getHeight());
            }
        } catch (Throwable t) {
            LiquidGlassModule.logErr("droplet sizing failed", t);
        }
    }

    /** Fingerprint of the bar geometry the droplet was last sized against. */
    private static int dropletGeometryKey(LiquidGlassHostLayout host, View tab) {
        int key = tab.getWidth();
        key = key * 131 + tab.getHeight();
        key = key * 131 + sPos[1];
        key = key * 131 + host.getHeight();
        return key;
    }

    /**
     * Re-places the droplet when the bar's own geometry moved under it.
     *
     * <p>Both apps rebuild parts of the bar on their own schedule — QQ restores
     * its docked tab heights, WeChat re-applies page metrics — and none of that
     * announces itself as a selection change. Without this the droplet keeps a
     * size and offset measured against the old bar until the next tab switch,
     * which is exactly the "glass sitting outside the bar" state.
     */
    private static void maintainDropletGeometry() {
        if (sLastIndex < 0 || sDropletRef.get() == null) {
            return;
        }
        ViewGroup tabRow = sTabRowRef.get();
        LiquidGlassHostLayout host = sHostRef.get();
        if (tabRow == null || host == null) {
            return;
        }
        View tab = TabBarBridge.tabAt(tabRow, sLastIndex);
        if (tab == null || !ViewGeom.positionIn(tab, host, sPos)) {
            return;
        }
        if (dropletGeometryKey(host, tab) == sDropletGeometryKey) {
            return;
        }
        syncDropletSize(sLastIndex);
    }
}
