package com.pojia.compositor.mobile

import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Like Mac PenInput.shared: reveal pressure controls after a pen is used in this app session. */
internal object PenInput {
    var seen by mutableStateOf(false)
        private set

    fun observe(event: MotionEvent) {
        if (seen) return
        for (index in 0 until event.pointerCount) {
            if (event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS ||
                event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER) {
                seen = true
                return
            }
        }
    }
}
