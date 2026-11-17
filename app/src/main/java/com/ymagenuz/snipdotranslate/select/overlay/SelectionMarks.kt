package com.ymagenuz.snipdotranslate.select.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.LinearInterpolator
import com.ymagenuz.snipdotranslate.select.core.Pt
import com.ymagenuz.snipdotranslate.select.core.Quad

// Apps report scrolling about this often, so each step glides into the next.
private const val STEP_MS = 100L
private const val FADE_MS = 150L

/**
 * The kept selection drawn over the live app while the user scrolls to the rest of it, where it
 * was on the frozen screen, in the colour of kept words. It only draws; touches go to the app below.
 * Its window starts [windowTop] pixels down the screen, below the status bar, so marks scrolled up
 * past it are cut off there instead of covering the bar.
 */
@SuppressLint("ViewConstructor")
class SelectionMarks(context: Context, private val marks: List<Quad>, private val windowTop: Float) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KEPT_COLOR }
    private val path = Path().apply { marks.forEach { addPath(it.toPath()) } }
    private var offset = 0f

    /** Follows the text after the app scrolled its content by [deltaY] pixels. */
    fun scrolledBy(deltaY: Int) {
        offset -= deltaY
        animate().translationY(offset).setDuration(STEP_MS).setInterpolator(LinearInterpolator()).start()
    }

    /** Where the kept words are on the screen now, after the scrolling followed so far. */
    fun keptNow(): List<Quad> = marks.map { quad ->
        fun Pt.moved() = Pt(x, y + offset)
        Quad(quad.topLeft.moved(), quad.topRight.moved(), quad.bottomRight.moved(), quad.bottomLeft.moved())
    }

    /** Fades out where the text can't be followed, then calls [after]. */
    fun fadeOut(after: () -> Unit) {
        animate().alpha(0f).setDuration(FADE_MS).withEndAction(after).start()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.translate(0f, -windowTop)
        canvas.drawPath(path, paint)
    }
}
