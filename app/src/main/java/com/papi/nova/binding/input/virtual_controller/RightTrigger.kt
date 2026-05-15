package com.papi.nova.binding.input.virtual_controller

import android.content.Context

class RightTrigger(controller: VirtualController, layer: Int, context: Context) :
    DigitalButton(controller, VirtualControllerElement.EID_RT, layer, context) {
    init {
        addDigitalButtonListener(object : DigitalButtonListener {
            override fun onClick() {
                val inputContext = controller.getControllerInputContext()
                inputContext.rightTrigger = 0xFF.toByte()
                controller.sendControllerInputContext()
            }

            override fun onLongClick() = Unit

            override fun onRelease() {
                val inputContext = controller.getControllerInputContext()
                inputContext.rightTrigger = 0x00.toByte()
                controller.sendControllerInputContext()
            }
        })
    }
}
