package io.github.lengroo.pddlg;

import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import java.util.ArrayDeque;

/**
 * Decides whether a visible page is a hybrid (web) page, on which any
 * RenderEffect on the glass node flashes the page white.
 *
 * <p>Two stages, in strict priority order:
 * <ol>
 *   <li>Exact class-name match (the 1.0.11 gate): a visible page whose class
 *       name contains the known hybrid container name or "WebView". This
 *       stays first and stays cheap — it runs every frame.</li>
 *   <li>Web-leaf fallback, only when stage 1 missed: walk the visible page's
 *       subtree looking for an actual web rendering leaf — a framework
 *       WebView instance, or any view whose simple class name contains
 *       "WebView" (covers X5 and other webview implementations, whose class
 *       names are not the host app's obfuscated ones). A renamed hybrid
 *       container still hosts such a leaf, so the gate still catches it;
 *       a renamed container with no web leaf is still treated as native.</li>
 * </ol>
 *
 * <p>The walk is bounded (node + depth caps) so it can never stall a frame,
 * and callers are expected to throttle it (run it on page change and
 * periodically while unlatched) rather than on every frame.
 */
public final class PageGate {
    /** Hard cap on visited nodes per walk. */
    public static final int MAX_WALK_NODES = 1500;
    /** Hard cap on subtree depth per walk. */
    public static final int MAX_WALK_DEPTH = 16;

    private PageGate() {}

    /** Stage 1: the 1.0.11 exact class-name gate, unchanged. */
    public static boolean exactHybridName(String className) {
        return className.contains("TouchEventInterceptView")
                || className.contains("WebView");
    }

    /** Stage 2: does this page subtree host a real web rendering leaf? */
    public static boolean containsWebLeaf(View root) {
        if (root == null) return false;
        ArrayDeque<View> stack = new ArrayDeque<>();
        ArrayDeque<Integer> depths = new ArrayDeque<>();
        stack.push(root);
        depths.push(0);
        int visited = 0;
        while (!stack.isEmpty()) {
            View v = stack.pop();
            int depth = depths.pop();
            if (++visited > MAX_WALK_NODES) break;
            if (v instanceof WebView) return true;
            if (v.getClass().getSimpleName().contains("WebView")) return true;
            if (v instanceof ViewGroup && depth < MAX_WALK_DEPTH) {
                ViewGroup g = (ViewGroup) v;
                for (int i = g.getChildCount() - 1; i >= 0; i--) {
                    View c = g.getChildAt(i);
                    if (c != null) {
                        stack.push(c);
                        depths.push(depth + 1);
                    }
                }
            }
        }
        return false;
    }
}
