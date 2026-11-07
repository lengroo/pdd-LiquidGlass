package io.github.lengroo.pddlg;

/**
 * Everything that differs between the apps this module can dress up.
 *
 * <p>This fork dresses exactly one app — Pinduoduo — but the table stays:
 * the installer, the glass renderer and the droplet are entirely
 * app-agnostic and work off view geometry, and what is not portable is
 * finding the bar in the first place. Pinduoduo runs its resource ids
 * through AndResGuard, so an id lookup by name is worthless, and the only
 * stable handles are the UI class names collected here. (The engine
 * underneath is liuran001's, built for WeChat and QQ; their entries are
 * gone from this build, and the seams they needed went with them.)
 */
final class HostApp {

    /**
     * Pinduoduo 8.29.0 (82900); the port target of this fork.
     *
     * <p>Structurally a twin of WeChat's frame, confirmed by a runtime
     * view-tree probe on a real device rather than by decompiling alone:
     * {@code MainFrameActivity} hosts a {@code MainFrameContainerView} whose
     * children are the full-height content FrameLayout (its pages already
     * extend under the bar), a 1px hairline, and the bar itself —
     * {@code PddTabView}, a horizontal LinearLayout holding five
     * {@code TabRelativeLayout} tabs. {@code HomeActivity} also resumes in
     * some entry flows, so it is accepted as a host too; the bar-class search
     * decides.
     *
     * <p>What differs from the other two, and shapes the seams elsewhere:
     * <ul>
     *   <li>No named switch method survives obfuscation — the bar's methods
     *   are single-letter overloads — but its own tab-change listener does:
     *   {@code setOnTabChangeListener(PddTabView$g_1)}, whose interface keeps
     *   {@code onTabSelected(int)}. {@link TabBarBridge} wraps that setter
     *   instead of hooking a switch method.
     *   <li>The tab item's fields are not obfuscated. The icon is the
     *   {@code tabImageView} field of {@code TabRelativeLayout} — a plain
     *   {@code ImageView}, indistinguishable by class from the badge dot,
     *   which is why this app resolves its icons by field rather than by
     *   class-name suffix. The label is {@code tabTitleView}; the badges are
     *   {@code dotView} and {@code digitalFlagView}.
     *   <li>The bar paints no background in {@code onDraw}; its solid colour
     *   is a regular background drawable, so the generic strip applies. A
     *   skin engine can re-apply one later, so the install re-strips whenever
     *   the tab structure is refreshed.
     *   <li>PDD sizes its frame to end at the navigation bar and manages its
     *   own insets, so the window is left entirely alone: no edge-to-edge
     *   hijack, no page stretching, no scroller padding — the pages already
     *   reach the bottom.
     * </ul>
     *
     * <p>{@code currentIndexMethod} names a method the bar does not declare:
     * it has no index getter, and the selection is delivered by the listener
     * above, with the tabs' own state as the fallback the reflective lookup
     * falls through to.
     */
    static final HostApp PDD = new HostApp(
            "com.xunmeng.pinduoduo",
            "com.xunmeng.pinduoduo.ui.activity.MainFrameActivity",
            new String[]{"com.xunmeng.pinduoduo.ui.activity.HomeActivity"},
            new String[]{
                    "com.xunmeng.pinduoduo.ui_home_activity.widget.tab.PddTabView",
            },
            new String[0],
            "getSelectedTab",
            new String[0],
            new String[0],
            "tabImageView",
            false,
            "com.xunmeng.pinduoduo.");

    private static final HostApp[] ALL = {PDD};

    /** Package name, which is also the name of the process the home screen lives in. */
    final String pkg;
    /** Activity hosting the tab bar; the only one worth reacting to. */
    final String launcherActivity;
    /** Further activities that may host the same frame in other entry flows. */
    final String[] extraLauncherActivities;
    /** Tab bar view classes, most likely first. */
    final String[] tabViewClasses;
    /** Methods called on the bar for every page switch, hooked as install triggers. */
    final String[] tabSwitchMethods;
    /**
     * Zero-arg method on the bar returning the selected index, if it has one.
     * A bar that does not is read through its tabs' selected state instead, so
     * this is an optimisation rather than a requirement.
     */
    final String currentIndexMethod;
    /** Sibling views to hide, so nothing is left between the glass and the page. */
    final String[] hiddenSiblings;
    /**
     * Class-name suffixes of the tab icon views.
     *
     * <p>Matched by suffix because neither app's icon class survives
     * obfuscation with its package intact. The droplet needs to know them:
     * the icon takes the accent tint along with the label, while everything
     * else in a tab (unread bubbles, red dots) keeps the colour it painted
     * itself. WeChat's icon is a custom view whose name ends in TabIconView;
     * QQ's is TabDragAnimationView, which is a bare {@code View} subclass and
     * would otherwise be mistaken for a badge.
     */
    final String[] iconClassSuffixes;
    /**
     * Field on the tab item holding its icon view, for apps whose icon class
     * is a plain framework widget that a suffix cannot single out; null when
     * the class suffixes above are the route.
     */
    final String iconFieldName;
    /**
     * Whether the tab labels' own colour beats the activity uiMode when
     * deciding light or dark.
     *
     * <p>WeChat resolves day/night through standard {@code values-night}
     * qualifiers, so its uiMode is authoritative and the label probe is only
     * logged as a cross-check. QQ ships a skin engine with a night mode of its
     * own that the uiMode does not track — a user can run QQ dark on a light
     * system — so there the labels are what the glass has to match.
     */
    final boolean preferTextColorProbe;
    /** Package prefix used to summarise the view tree when the bar is not found. */
    final String uiPrefix;

    private HostApp(String pkg, String launcherActivity,
                    String[] extraLauncherActivities, String[] tabViewClasses,
                    String[] tabSwitchMethods, String currentIndexMethod,
                    String[] hiddenSiblings, String[] iconClassSuffixes,
                    String iconFieldName,
                    boolean preferTextColorProbe, String uiPrefix) {
        this.pkg = pkg;
        this.launcherActivity = launcherActivity;
        this.extraLauncherActivities = extraLauncherActivities;
        this.tabViewClasses = tabViewClasses;
        this.tabSwitchMethods = tabSwitchMethods;
        this.currentIndexMethod = currentIndexMethod;
        this.hiddenSiblings = hiddenSiblings;
        this.iconClassSuffixes = iconClassSuffixes;
        this.iconFieldName = iconFieldName;
        this.preferTextColorProbe = preferTextColorProbe;
        this.uiPrefix = uiPrefix;
    }

    /**
     * The app this process belongs to, or null for one we do not dress up.
     *
     * <p>Matched on the exact process name rather than the package: both apps
     * are heavily multi-process ({@code :push}, {@code :tools}, {@code :MSF},
     * {@code :appbrandX}), and the home screen only ever lives in the main
     * process, which is named after the package.
     */
    static HostApp forProcess(String processName) {
        for (HostApp app : ALL) {
            if (app.pkg.equals(processName)) {
                return app;
            }
        }
        return null;
    }

    /** The app owning {@code packageName}, or null. */
    static HostApp forPackage(String packageName) {
        for (HostApp app : ALL) {
            if (app.pkg.equals(packageName)) {
                return app;
            }
        }
        return null;
    }

    /** Whether {@code className} is an activity that hosts this app's frame. */
    boolean isLauncherActivity(String className) {
        if (launcherActivity.equals(className)) {
            return true;
        }
        for (String extra : extraLauncherActivities) {
            if (extra.equals(className)) {
                return true;
            }
        }
        return false;
    }

    boolean isTabViewClass(String className) {
        for (String c : tabViewClasses) {
            if (c.equals(className)) {
                return true;
            }
        }
        return false;
    }

    boolean isHiddenSibling(String className) {
        for (String c : hiddenSiblings) {
            if (c.equals(className)) {
                return true;
            }
        }
        return false;
    }

    boolean isTabIconClass(String className) {
        for (String suffix : iconClassSuffixes) {
            if (className.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return pkg;
    }
}
