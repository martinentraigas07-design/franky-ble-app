package com.franky.robot.ui.fragments

import android.os.Bundle
import android.view.*
import android.widget.Button
import androidx.fragment.app.Fragment
import com.franky.robot.R
import com.franky.robot.ui.components.JoystickView

class GamepadFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        return inflater.inflate(R.layout.fragment_gamepad, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        val up = view.findViewById<Button>(R.id.btnUp)
        val down = view.findViewById<Button>(R.id.btnDown)
        val left = view.findViewById<Button>(R.id.btnLeft)
        val right = view.findViewById<Button>(R.id.btnRight)
        val joy = view.findViewById<JoystickView>(R.id.joystickView)

        up.setOnClickListener { }
        down.setOnClickListener { }
        left.setOnClickListener { }
        right.setOnClickListener { }

        joy.onMove = { l, r ->
            println("M:$l,$r")
        }
    }
}
