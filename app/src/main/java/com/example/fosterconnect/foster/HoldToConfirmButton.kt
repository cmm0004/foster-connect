package com.example.fosterconnect.foster

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.example.fosterconnect.R

class HoldToConfirmButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : androidx.appcompat.widget.AppCompatTextView(context, attrs, defStyleAttr) {

    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var onConfirmed: (() -> Unit)? = null

    private val fillColor = ContextCompat.getColor(context, R.color.clinical_crimson)
    private val strokeColor = ContextCompat.getColor(context, R.color.clinical_crimson)
    private val textIdleColor = ContextCompat.getColor(context, R.color.clinical_crimson)
    private val textFilledColor = ContextCompat.getColor(context, R.color.white)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = strokeColor
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    private val cornerRadius = 5f * resources.displayMetrics.density
    private val rect = RectF()
    private val fillRect = RectF()
    private val holdDurationMs = 1500L

    private var idleText: CharSequence = ""
    private var holdingText: CharSequence = ""

    init {
        idleText = context.getString(R.string.hold_to_complete)
        holdingText = context.getString(R.string.hold_to_complete_active)
        text = idleText
        setTextColor(textIdleColor)
        gravity = android.view.Gravity.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textSize = 14f
        val hPad = (24 * resources.displayMetrics.density).toInt()
        val vPad = (12 * resources.displayMetrics.density).toInt()
        setPadding(hPad, vPad, hPad, vPad)
    }

    fun setOnConfirmedListener(listener: () -> Unit) {
        onConfirmed = listener
    }

    override fun onDraw(canvas: Canvas) {
        val inset = strokePaint.strokeWidth / 2f
        rect.set(inset, inset, width - inset, height - inset)

        if (progress > 0f) {
            fillRect.set(rect.left, rect.top, rect.left + rect.width() * progress, rect.bottom)
            canvas.save()
            canvas.clipRect(fillRect)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
            canvas.restore()
        }

        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)

        super.onDraw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startFilling()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelFilling()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun startFilling() {
        animator?.cancel()
        text = holdingText
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = holdDurationMs
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                progress = anim.animatedValue as Float
                setTextColor(blendColors(textIdleColor, textFilledColor, progress))
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (progress >= 1f) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        onConfirmed?.invoke()
                    }
                }
            })
            start()
        }
    }

    private fun cancelFilling() {
        animator?.cancel()
        progress = 0f
        text = idleText
        setTextColor(textIdleColor)
        invalidate()
    }

    private fun blendColors(from: Int, to: Int, ratio: Float): Int {
        val inv = 1f - ratio
        val a = (android.graphics.Color.alpha(from) * inv + android.graphics.Color.alpha(to) * ratio).toInt()
        val r = (android.graphics.Color.red(from) * inv + android.graphics.Color.red(to) * ratio).toInt()
        val g = (android.graphics.Color.green(from) * inv + android.graphics.Color.green(to) * ratio).toInt()
        val b = (android.graphics.Color.blue(from) * inv + android.graphics.Color.blue(to) * ratio).toInt()
        return android.graphics.Color.argb(a, r, g, b)
    }
}
