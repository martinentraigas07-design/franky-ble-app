package com.franky.robot.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Virtual joystick — matches the .jzone / .jknob style from gamepad.html.
 * Colors: bg radial gradient #222→#0D0D0D, knob bg #2A2A2A border #E05A00
 */
class JoystickView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    // Geometry
    private var cx = 0f
    private var cy = 0f
    private var baseRadius = 0f
    private var knobRadius = 0f
    private var knobX = 0f
    private var knobY = 0f

    // Paints
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val baseBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF333333.toInt()
        strokeWidth = 3f
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF2A2A2A.toInt()
    }
    private val knobBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFE05A00.toInt()
        strokeWidth = 3f
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE05A00.toInt()
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }

    // Callbacks
    var onMove: ((leftMotor: Int, rightMotor: Int) -> Unit)? = null
    var onStop: (() -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        cx = w / 2f
        cy = h / 2f
        baseRadius = min(w, h) / 2f * 0.92f
        knobRadius = baseRadius * 0.38f
        knobX = cx
        knobY = cy

        // Radial gradient for base — matches CSS radial-gradient(circle at center, #222 0%, #0D0D0D 100%)
        basePaint.shader = RadialGradient(
            cx, cy, baseRadius,
            intArrayOf(0xFF222222.toInt(), 0xFF0D0D0D.toInt()),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Base circle
        canvas.drawCircle(cx, cy, baseRadius, basePaint)
        canvas.drawCircle(cx, cy, baseRadius, baseBorderPaint)

        // Knob
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint)
        canvas.drawCircle(knobX, knobY, knobRadius, knobBorderPaint)

        // Cross mark on knob center
        val arm = knobRadius * 0.35f
        canvas.drawLine(knobX - arm, knobY, knobX + arm, knobY, crossPaint)
        canvas.drawLine(knobX, knobY - arm, knobX, knobY + arm, crossPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                var dx = event.x - cx
                var dy = event.y - cy
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > baseRadius) {
                    dx = dx / dist * baseRadius
                    dy = dy / dist * baseRadius
                }
                knobX = cx + dx
                knobY = cy + dy

                val nx = (knobX - cx) / baseRadius
                val ny = -(knobY - cy) / baseRadius

                if (abs(nx) < 0.05f && abs(ny) < 0.05f) {
                    onStop?.invoke()
                } else {
                    // Differential steering mix
                    var lm = ny + nx
                    var rm = ny - nx
                    val maxVal = max(abs(lm), abs(rm))
                    if (maxVal > 1f) {
                        lm /= maxVal
                        rm /= maxVal
                    }
                    onMove?.invoke((lm * 255).toInt(), (rm * 255).toInt())
                }

                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                knobX = cx
                knobY = cy
                onStop?.invoke()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
