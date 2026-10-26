/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal fun exactEldMethod(owner: Class<*>, name: String, result: Class<*>,
    parameters: List<Class<*>>, static: Boolean = false): Method {
    val candidates = owner.declaredMethods.filter { it.name == name }
    require(candidates.size == 1) { "Unknown or overloaded $name" }
    return candidates.single().also {
        require(it.returnType == result && it.parameterTypes.toList() == parameters &&
            Modifier.isStatic(it.modifiers) == static && !it.isVarArgs) { "Unknown signature for $name" }
        it.isAccessible = true
    }
}

/** Copies only this carrier; it does not hook or modify BluetoothCodecConfig constructors. */
internal class EldCodecObjects(private val codec: Class<*>, private val extendedType: Class<*>? = null,
    private val readNativeIndex: () -> Int? = { null }) {
    val extended: Boolean get() = extendedType != null
    val arrayClass: Class<*> = ReflectArray.newInstance(codec, 0).javaClass
    private val names = listOf("getCodecType", "getCodecPriority", "getSampleRate", "getBitsPerSample",
        "getChannelMode", "getCodecSpecific1", "getCodecSpecific2", "getCodecSpecific3", "getCodecSpecific4")
    private val types = List(5) { Int::class.javaPrimitiveType!! } + List(4) { Long::class.javaPrimitiveType!! }
    private val getters = names.mapIndexed { i, name -> exactEldMethod(codec, name, types[i], emptyList()) }
    private val constructor = codec.getDeclaredConstructor(*types.toTypedArray()).apply { isAccessible = true }
    private val extendedGetter = extendedType?.let { exactEldMethod(codec, "getExtendedCodecType", it, emptyList()) }
    private val extendedIndex = extendedType?.let {
        exactEldMethod(it, "getNativeCodecType", Int::class.javaPrimitiveType!!, emptyList())
    }
    private val extendedId = extendedType?.let {
        exactEldMethod(it, "getCodecId", Long::class.javaPrimitiveType!!, emptyList())
    }
    private val extendedConstructor = extendedType?.getDeclaredConstructor(Int::class.javaPrimitiveType,
        Long::class.javaPrimitiveType, String::class.java)?.apply { isAccessible = true }
    private val extendedConfig = extendedType?.let {
        codec.getDeclaredConstructor(it, *types.drop(1).toTypedArray()).apply { isAccessible = true }
    }
    private val stockType = extendedType?.let {
        exactEldMethod(it, "createFromType", it, listOf(Int::class.javaPrimitiveType!!), static = true)
    }

    init {
        val probe = EldCodecIdentity.Fields(if (extended) Int.MAX_VALUE else EldCodecIdentity.MODULE_CODEC_ID,
            -1, 2, 1, 2, Long.MIN_VALUE, Long.MAX_VALUE, -1, 0x123456789abcdefL,
            if (extended) EldCodecIdentity.APPLE_CODEC_ID else null)
        require(fields(create(probe)) == probe) { "Codec carrier does not preserve its fields" }
    }

    fun fields(value: Any): EldCodecIdentity.Fields {
        require(codec.isInstance(value)) { "Unknown codec carrier" }
        val v = getters.map { it.invoke(value) }
        val type = extendedGetter?.invoke(value)
        if (type != null) require(extendedIndex!!.invoke(type) == v[0]) { "Codec type getters disagree" }
        return EldCodecIdentity.Fields(v[0] as Int, v[1] as Int, v[2] as Int, v[3] as Int,
            v[4] as Int, v[5] as Long, v[6] as Long, v[7] as Long, v[8] as Long,
            type?.let { extendedId!!.invoke(it) as Long })
    }

    private fun create(v: EldCodecIdentity.Fields, originalType: Any? = null): Any {
        if (!extended) return constructor.newInstance(v.type, v.priority, v.sampleRate, v.bitsPerSample,
            v.channelMode, v.specific1, v.specific2, v.specific3, v.specific4)
        val type = when {
            originalType != null && extendedIndex!!.invoke(originalType) == v.type &&
                extendedId!!.invoke(originalType) == v.extendedCodecId -> originalType
            v.extendedCodecId == EldCodecIdentity.APPLE_CODEC_ID ->
                extendedConstructor!!.newInstance(v.type, v.extendedCodecId, "Apple AAC-ELD")
            else -> stockType!!.invoke(null, v.type).also {
                require(it != null && extendedIndex!!.invoke(it) == v.type &&
                    extendedId!!.invoke(it) == v.extendedCodecId) { "Unknown stock codec type" }
            }
        }
        return extendedConfig!!.newInstance(type, v.priority, v.sampleRate, v.bitsPerSample,
            v.channelMode, v.specific1, v.specific2, v.specific3, v.specific4)
    }

    fun nativeIndex(): Int? = if (extended) readNativeIndex()?.takeIf { it in 0..11 } else EldCodecIdentity.NATIVE_SLOT

    fun inbound(value: EldCodecIdentity.Fields, ready: Boolean): EldCodecIdentity.Fields? =
        if (extended) EldCodecIdentity.extendedInbound(value, ready, if (ready) nativeIndex() else null)
        else EldCodecIdentity.inbound(value, ready)

    fun outbound(value: EldCodecIdentity.Fields): EldCodecIdentity.Fields {
        if (!extended) return EldCodecIdentity.outbound(value, true)
        require(value.extendedCodecId != EldCodecIdentity.APPLE_CODEC_ID || value.type == nativeIndex()) {
            "Native ELD codec identity is inconsistent"
        }
        return value
    }

    fun offload(value: EldCodecIdentity.Fields, ready: Boolean): EldCodecIdentity.Fields? =
        if (extended) EldCodecIdentity.extendedOffload(value) else EldCodecIdentity.offload(value, ready)

    fun isEld(value: Any): Boolean = fields(value).let {
        if (extended) it.extendedCodecId == EldCodecIdentity.APPLE_CODEC_ID && it.type == nativeIndex()
        else it.type == EldCodecIdentity.MODULE_CODEC_ID
    }

    fun aacFallback(): Any = ReflectArray.newInstance(codec, 1).also {
        ReflectArray.set(it, 0, create(EldCodecIdentity.Fields(1, 1_000_000, 0, 0, 0, 0, 0, 0, 0,
            if (extended) 2L else null)))
    }

    fun map(value: Any?, transform: (EldCodecIdentity.Fields) -> EldCodecIdentity.Fields?): Any? {
        if (value == null) return null
        val before = fields(value)
        val after = transform(before) ?: return null
        return if (after === before || after == before) value else create(after, extendedGetter?.invoke(value))
    }

    data class Mapping(val value: Any?, val changed: Boolean, val hasCodecs: Boolean)
    fun array(value: Any?, transform: (EldCodecIdentity.Fields) -> EldCodecIdentity.Fields?): Mapping {
        if (value == null) return Mapping(null, false, false)
        require(value.javaClass == arrayClass) { "Unknown codec array" }
        val size = ReflectArray.getLength(value)
        val mapped = List(size) { i -> map(ReflectArray.get(value, i), transform) }
        val changed = mapped.indices.any { mapped[it] !== ReflectArray.get(value, it) }
        val result = if (!changed) value else ReflectArray.newInstance(codec, size).also { result ->
            mapped.forEachIndexed { i, entry -> ReflectArray.set(result, i, entry) }
        }
        return Mapping(result, changed, mapped.any { it != null })
    }

    fun priorities(value: Any?, ready: Boolean): Any? {
        if (value == null || !ready) return value
        require(value.javaClass == arrayClass) { "Unknown priority array" }
        val entries = List(ReflectArray.getLength(value)) { ReflectArray.get(value, it) }
        val values = entries.map { it?.let(::fields) }
        val selected = if (!extended) EldCodecIdentity.priorities(values, true) else {
            val index = nativeIndex() ?: return value
            require(values.none { it != null && it.type == index && it.extendedCodecId != EldCodecIdentity.APPLE_CODEC_ID }) {
                "ELD source index conflicts with a stock codec"
            }
            if (values.any { it?.extendedCodecId == EldCodecIdentity.APPLE_CODEC_ID }) values
            else values + EldCodecIdentity.Fields(index, EldCodecIdentity.INITIAL_PRIORITY,
                0, 0, 0, 0, 0, 0, 0, EldCodecIdentity.APPLE_CODEC_ID)
        }
        if (selected === values) return value
        return ReflectArray.newInstance(codec, selected.size).also { result ->
            entries.forEachIndexed { i, entry -> ReflectArray.set(result, i, entry) }
            ReflectArray.set(result, entries.size, create(checkNotNull(selected.last())))
        }
    }
}
