package me.kavishdevar.librepods.bluetooth

import android.bluetooth.BluetoothSocket
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowBluetoothSocket

@Implements(BluetoothSocket::class)
open class ClosingBluetoothSocketShadow : ShadowBluetoothSocket() {
    // Robolectric can retain the previous custom shadow's close call site across suites.
    // One shared receiver avoids casting ProtocolReportSocketShadow to HearingModeSocketShadow.
    @Implementation
    public final override fun close() {
        onClosing()
        super.close()
        inputStreamFeeder.close()
        outputStreamSink.close()
    }

    protected open fun onClosing() = Unit
}
