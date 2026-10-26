package me.kavishdevar.librepods.shizuku

/** These checks also run in the shell process; Binder parameters are not trusted. */
internal object AudioBridgeRules {
    fun helperOwnsRequest(expectedHelperUid: Int?, callingUid: Int): Boolean =
        expectedHelperUid in setOf(0, 2000) && callingUid == expectedHelperUid

    fun mayReconnectRoot(granted: Boolean?, explicitlyAuthorized: Boolean, attempted: Boolean = false): Boolean =
        granted == true || (!attempted && granted == null && explicitlyAuthorized)

    fun requireCaller(expectedUid: Int, callingUid: Int) {
        require(expectedUid >= 10000 && callingUid == expectedUid) {
            "Audio bridge caller does not own LibrePods"
        }
    }
}
