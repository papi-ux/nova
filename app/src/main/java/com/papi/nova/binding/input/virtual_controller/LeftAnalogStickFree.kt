package com.papi.nova.binding.input.virtual_controller

import android.content.Context
import com.papi.nova.nvstream.input.ControllerPacket

class LeftAnalogStickFree(controller: VirtualController, context: Context) :
    AnalogStickFree(controller, context, VirtualControllerElement.EID_LS) {
    init {
        strStickSide = "L"

        addAnalogStickListener(object : AnalogStickListener {
            override fun onMovement(x: Float, y: Float) {
                val inputContext = controller.getControllerInputContext()
                inputContext.leftStickX = (x * 0x7FFE).toInt().toShort()
                inputContext.leftStickY = (y * 0x7FFE).toInt().toShort()
                controller.sendControllerInputContext(10, 0x11)
            }

            override fun onClick() = Unit

            override fun onDoubleClick() {
                val inputContext = controller.getControllerInputContext()
                inputContext.inputMap = inputContext.inputMap or ControllerPacket.LS_CLK_FLAG
                controller.sendControllerInputContext()
            }

            override fun onRevoke() {
                val inputContext = controller.getControllerInputContext()
                inputContext.inputMap = inputContext.inputMap and ControllerPacket.LS_CLK_FLAG.inv()
                controller.sendControllerInputContext()
            }
        })
    }
}
