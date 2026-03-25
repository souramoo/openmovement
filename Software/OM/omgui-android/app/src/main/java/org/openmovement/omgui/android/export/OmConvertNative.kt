package org.openmovement.omgui.android.export

object OmConvertNative {
    init {
        System.loadLibrary("omgui-native")
    }

    external fun runOmconvert(arguments: Array<String>): Int
}

