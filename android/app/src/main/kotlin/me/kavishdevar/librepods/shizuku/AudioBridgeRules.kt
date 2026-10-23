package me.kavishdevar.librepods.shizuku

/** These checks also run in the shell process; Binder parameters are not trusted. */
internal object AudioBridgeRules {
    fun requireCaller(expectedUid: Int, callingUid: Int) {
        require(expectedUid >= 10000 && callingUid == expectedUid) {
            "Audio bridge caller does not own LibrePods"
        }
    }
}
