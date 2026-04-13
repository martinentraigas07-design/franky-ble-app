package com.franky.robot.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.sqrt

class JoystickView(context: Context) : View(context) {

    private val paint = Paint()
    private var cx = 0f
    private var cy = 0f

    var onMove: ((Int, Int) -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        cx = width / 2f
        cy = height / 2f
        paint.color = Color.GRAY
        canvas.drawCircle(cx, cy, width / 3f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val dx = event.x - cx
        val dy = cy - event.y

        val x = (dx / cx * 255).toInt()
        val y = (dy / cy * 255).toInt()

        val left = y + x
        val right = y - x

        onMove?.invoke(left, right)

        return true
    }
}
