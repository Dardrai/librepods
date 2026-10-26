/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

/** Verified Android 13 and 17 API shapes; unknown signatures/overloads do not get hooked. */
internal class EldCodecHookPlan(native: Class<*>, config: Class<*>, codec: Class<*>, device: Class<*>,
    callback: Class<*> = native, extendedType: Class<*>? = null, readNativeIndex: () -> Int? = { null }) {
    val objects = EldCodecObjects(codec, extendedType, readNativeIndex)
    private val array = objects.arrayClass
    val init = exactEldMethod(native, "init", Void.TYPE,
        listOf(Int::class.javaPrimitiveType!!, array, array) + if (objects.extended) listOf(List::class.java) else emptyList())
    val outbound = exactEldMethod(callback, "onCodecConfigChanged", Void.TYPE,
        listOf(ByteArray::class.java, codec, array, array))
    val preference = exactEldMethod(native, "setCodecConfigPreference", Boolean::class.javaPrimitiveType!!,
        listOf(device, array))
    val priorities = exactEldMethod(config, "assignCodecConfigPriorities", array, emptyList())
    val bufferSwitch = if (objects.extended) null else exactEldMethod(config, "switchCodecByBufferSize", Void.TYPE,
        listOf(device, Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!))
    val name = if (objects.extended) null else exactEldMethod(codec, "getCodecName", String::class.java,
        listOf(Int::class.javaPrimitiveType!!), static = true)
    val active = exactEldMethod(native, "setActiveDevice", Boolean::class.javaPrimitiveType!!, listOf(device))
    val address = exactEldMethod(native, "getByteAddress", ByteArray::class.java, listOf(device))
    val audio = exactEldMethod(callback, "onAudioStateChanged", Void.TYPE,
        listOf(ByteArray::class.java, Int::class.javaPrimitiveType!!))
    val connection = exactEldMethod(callback, "onConnectionStateChanged", Void.TYPE,
        listOf(ByteArray::class.java, Int::class.javaPrimitiveType!!) +
            if (objects.extended) listOf(Int::class.javaPrimitiveType!!) else emptyList())
    val cleanup = exactEldMethod(native, "cleanup", Void.TYPE, emptyList())

    companion object {
        fun discover(loader: ClassLoader, sdk: Int, readNativeIndex: () -> Int? = { null }): EldCodecHookPlan {
            require(sdk == 33 || sdk == 37) { "Unknown Bluetooth codec API level" }
            val native = Class.forName("com.android.bluetooth.a2dp.A2dpNativeInterface", false, loader)
            return EldCodecHookPlan(native,
                Class.forName("com.android.bluetooth.a2dp.A2dpCodecConfig", false, loader),
                Class.forName("android.bluetooth.BluetoothCodecConfig", false, loader),
                Class.forName("android.bluetooth.BluetoothDevice", false, loader),
                if (sdk == 37) Class.forName("com.android.bluetooth.a2dp.A2dpNativeCallback", false, loader) else native,
                if (sdk == 37) Class.forName("android.bluetooth.BluetoothCodecType", false, loader) else null,
                readNativeIndex)
        }
    }
}

internal class EldCodecBoundary(private val objects: EldCodecObjects) {
    /** Checks the untouched initial SDK37 catalog before native slot admission, including when
     * readiness is still false. A missing/unknown carrier cannot prove that slot 5 is free. */
    fun initialCodecSlotFree(args: Array<Any?>): Boolean {
        if (!objects.extended) return true
        return runCatching {
            require(args.size == 4 && args[0] is Int && args[3] is List<*>) { "Unknown initialization schema" }
            listOf(args[1], args[2]).all { value ->
                require(value != null && value.javaClass == objects.arrayClass) { "Unknown initial codec array" }
                (value as Array<*>).all { entry ->
                    require(entry != null) { "Unknown initial codec entry" }
                    val fields = objects.fields(entry)
                    require(fields.extendedCodecId != null) { "Unknown initial codec identity" }
                    fields.type != EldCodecIdentity.NATIVE_SLOT ||
                        fields.extendedCodecId == EldCodecIdentity.APPLE_CODEC_ID
                }
            }
        }.getOrDefault(false)
    }

    fun outbound(args: Array<Any?>, ready: Boolean): Array<Any?> {
        if (!ready) return args
        require(args.size == 4)
        return args.copyOf().also {
            it[1] = objects.map(it[1], objects::outbound)
            it[2] = objects.array(it[2], objects::outbound).value
            it[3] = objects.array(it[3], objects::outbound).value
        }
    }

    fun init(args: Array<Any?>, ready: Boolean): Array<Any?> {
        require(args.size == if (objects.extended) 4 else 3)
        if (objects.extended) require(args[3] is List<*>) { "Unknown paired-device list" }
        return args.copyOf().also {
            // The field may have been cached before native readiness became true.
            val priorities = objects.priorities(it[1], ready)
            it[1] = objects.array(priorities) { v -> objects.inbound(v, ready) }.value
            it[2] = objects.array(it[2]) { v -> objects.offload(v, ready) }.value
        }
    }

    /** Null rejects only a request whose codecs were all removed by our identity guard. */
    fun preference(args: Array<Any?>, ready: Boolean): Array<Any?>? {
        require(args.size == 2)
        val mapped = objects.array(args[1]) { v -> objects.inbound(v, ready) }
        if (mapped.changed && !mapped.hasCodecs) return null
        return args.copyOf().also { it[1] = mapped.value }
    }

    // The pinned Note8 implementation targets Opus 6, not AOSP's LC3 5.
    // Leave that supported stock policy intact unless it would displace our current codec.
    fun suppressBufferSwitch(currentCodec: Int, ready: Boolean) =
        !objects.extended && ready && currentCodec == EldCodecIdentity.MODULE_CODEC_ID
}
