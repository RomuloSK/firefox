/* This Source Code Form is subject to the terms of the Mozilla Public
   - License, v. 2.0. If a copy of the MPL was not distributed with this
   - file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.tv

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.children

/**
 * Free-roaming pointer for Android TV.
 *
 * Fenix assumes a touch screen: the toolbar, menus and page content all expect taps at
 * coordinates. Focus-walking alone cannot reach all of that (and modal widgets trap
 * D-pad focus), so a TV remote needs a pointer that can go anywhere on screen, like
 * the cursor in other 10-foot browsers.
 *
 * The D-pad moves a visible cursor, OK taps at that position, and pressing towards a
 * screen edge scrolls the page. Because taps are synthesized as real touch events into
 * GeckoView, hover menus, sliders, video controls and the app's own toolbar all behave
 * exactly as they do for a finger.
 */
class TvCursorController(
    private val root: ViewGroup,
    initialX: Float,
    initialY: Float,
) {
    private val density = root.resources.displayMetrics.density
    private val cursorRadiusPx = 18f * density
    private val stepPx = 26f * density
    private val fastStepPx = 52f * density

    /** Pixels from the edge where a held direction starts scrolling instead of moving. */
    private val edgePx = 44f * density

    private var cachedGecko: View? = null

    private var x = initialX
    private var y = initialY
    private var visible = false

    private val cursorView = CursorView()

    private val paintRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.WHITE
    }

    private val paintGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f * density
        color = Color.parseColor("#7CC7FF")
        alpha = 90
    }

    private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    init {
        cursorView.visibility = View.GONE
        root.addView(
            cursorView,
            FrameLayout.LayoutParams(
                (cursorRadiusPx * 2).toInt(),
                (cursorRadiusPx * 2).toInt(),
            ),
        )
    }

    /**
     * The GeckoView currently on screen, if any.
     *
     * Cached: walking the view tree on every key press is wasteful, and this runs on
     * the UI thread where input dispatch has a strict budget.
     */
    private fun geckoView(): View? {
        cachedGecko?.let { if (it.isAttachedToWindow && it.isShown) return it }
        cachedGecko = findGeckoView()
        return cachedGecko
    }

    private fun findGeckoView(view: View? = root): View? {
        if (view == null) return null
        // Fenix wraps GeckoView in an anonymous subclass, so match by type.
        if (view is org.mozilla.geckoview.GeckoView) return view
        if (view is ViewGroup) {
            for (child in view.children) {
                findGeckoView(child)?.let { return it }
            }
        }
        return null
    }

    /** Hides the pointer if shown. @return true when the pointer was visible. */
    fun hideIfVisible(): Boolean {
        if (!visible) return false
        hide()
        return true
    }

    fun detach() {
        (cursorView.parent as? ViewGroup)?.removeView(cursorView)
    }

    /**
     * @return true when the key was consumed by the cursor.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        val gecko = geckoView() ?: return false
        if (!gecko.isShown) return false

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (!visible) show(gecko)
                    move(event, gecko)
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (!visible) show(gecko)
                    tap(gecko)
                }
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                // Back hides the cursor first, so it never traps the user in a page.
                // Consume the whole gesture (down and up): the framework acts on
                // ACTION_DOWN, so consuming only ACTION_UP lets the app exit.
                if (visible) {
                    if (event.action == KeyEvent.ACTION_UP) hide()
                    return true
                }
                return false
            }
        }
        return false
    }

    private fun show(gecko: View) {
        visible = true
        cursorView.visibility = View.VISIBLE
        // Start from the middle of the content if we have never been moved.
        if (x <= 0f || y <= 0f) {
            val loc = IntArray(2)
            gecko.getLocationOnScreen(loc)
            x = loc[0] + gecko.width / 2f
            y = loc[1] + gecko.height / 2f
        }
        positionCursor()
        sendHover(gecko)
    }

    private fun hide() {
        visible = false
        cursorView.visibility = View.GONE
    }

    private fun move(event: KeyEvent, gecko: View) {
        val step = if (event.repeatCount > 4) fastStepPx else stepPx
        var dx = 0f
        var dy = 0f
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> dy = -step
            KeyEvent.KEYCODE_DPAD_DOWN -> dy = step
            KeyEvent.KEYCODE_DPAD_LEFT -> dx = -step
            KeyEvent.KEYCODE_DPAD_RIGHT -> dx = step
        }

        val screenW = root.width.toFloat()
        val screenH = root.height.toFloat()
        val nx = (x + dx).coerceIn(cursorRadiusPx, screenW - cursorRadiusPx)
        val ny = (y + dy).coerceIn(cursorRadiusPx, screenH - cursorRadiusPx)

        val atEdge = (nx == x && dx != 0f) || (ny == y && dy != 0f)
        if (atEdge) {
            // At the screen edge there is nowhere left to point, so treat the held
            // direction as "keep reading": scroll the page.
            if (dy > 0) sendKey(gecko, KeyEvent.KEYCODE_PAGE_DOWN)
            if (dy < 0) sendKey(gecko, KeyEvent.KEYCODE_PAGE_UP)
            return
        }

        // Near an edge, holding the direction scrolls the page, which is how a user
        // reads a long article without hunting for a scrollbar.
        val nearTop = y < edgePx
        val nearBottom = y > screenH - edgePx
        if (dy > 0 && nearBottom) {
            sendKey(gecko, KeyEvent.KEYCODE_PAGE_DOWN)
        } else if (dy < 0 && nearTop) {
            sendKey(gecko, KeyEvent.KEYCODE_PAGE_UP)
        }

        x = nx
        y = ny
        positionCursor()
        sendHover(gecko)
    }

    private fun positionCursor() {
        cursorView.x = x - cursorRadiusPx
        cursorView.y = y - cursorRadiusPx
    }

    /** Convert window coordinates into the GeckoView's local space. */
    private fun localPoint(gecko: View): Pair<Float, Float> {
        val loc = IntArray(2)
        gecko.getLocationOnScreen(loc)
        return (x - loc[0]) to (y - loc[1])
    }

    private fun sendHover(gecko: View) {
        val (lx, ly) = localPoint(gecko)
        // Never dispatch into Gecko synchronously from a key event: during page load it
        // can block long enough to trip the input-dispatch ANR watchdog.
        gecko.post {
            val now = SystemClock.uptimeMillis()
            val hover = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, lx, ly, 0)
            gecko.dispatchGenericMotionEvent(hover)
            hover.recycle()
        }
    }

    private fun tap(gecko: View) {
        // Dispatch through the root so the tap reaches whatever is under the cursor:
        // page content, Fenix's toolbar or a menu. Routing straight to GeckoView would
        // make the app's own chrome unreachable, which is the main reason a TV browser
        // needs a pointer at all.
        val cx = x
        val cy = y
        root.post {
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, cx, cy, 0)
            root.dispatchTouchEvent(down)
            down.recycle()

            val up = MotionEvent.obtain(downTime, downTime + 60, MotionEvent.ACTION_UP, cx, cy, 0)
            root.dispatchTouchEvent(up)
            up.recycle()
        }
    }

    private fun sendKey(gecko: View, keyCode: Int) {
        gecko.post {
            val now = SystemClock.uptimeMillis()
            gecko.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            gecko.dispatchKeyEvent(KeyEvent(now, now + 40, KeyEvent.ACTION_UP, keyCode, 0))
        }
    }

    private inner class CursorView : View(root.context) {
        init {
            isClickable = false
            isFocusable = false
            setWillNotDraw(false)
        }

        @SuppressLint("DrawAllocation")
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, cursorRadiusPx - 4f * density, paintGlow)
            canvas.drawCircle(cx, cy, cursorRadiusPx - 8f * density, paintRing)
            canvas.drawCircle(cx, cy, 4f * density, paintDot)
        }
    }
}
