package me.kavishdevar.librepods.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import java.io.Closeable

/** Owns only its binding. Late root grants and late service callbacks cannot revive a retired client. */
internal class RootAudioConnection(
    context: Context,
    private val connected: (IBinder) -> Unit,
    private val disconnected: () -> Unit,
    private val unavailable: () -> Unit,
    private val authorize: ((Boolean) -> Unit) -> Unit = { done ->
        Shell.EXECUTOR.execute {
            done(runCatching { acceptRootShell(Shell.getShell()) }.getOrDefault(false))
        }
    },
    private val bind: (ServiceConnection) -> Unit = {
        RootService.bind(Intent(context, RootAudioService::class.java), it)
    },
    private val unbind: (ServiceConnection) -> Unit = RootService::unbind,
) : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private var attempt: Attempt? = null
    @Volatile private var closed = false

    private inner class Attempt : ServiceConnection {
        var bound = false
        val timeout = Runnable { fail(this) }
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (closed || attempt !== this) { release(this); return }
            main.removeCallbacks(timeout)
            connected(service)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            if (attempt !== this) return
            attempt = null
            release(this)
            if (!closed) disconnected()
        }
        override fun onNullBinding(name: ComponentName) = fail(this)
        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
    }

    fun start() = main.post {
        if (closed || attempt != null) return@post
        val current = Attempt().also { attempt = it }
        main.postDelayed(current.timeout, 15_000)
        runCatching { authorize { granted -> main.post grantResult@{
            if (closed || attempt !== current) return@grantResult
            if (!granted) { fail(current); return@grantResult }
            runCatching { current.bound = true; bind(current) }.onFailure { fail(current) }
        } } }.onFailure { fail(current) }
    }

    private fun fail(current: Attempt) {
        if (attempt !== current) return
        attempt = null
        release(current)
        if (!closed) unavailable()
    }

    private fun release(current: Attempt) {
        main.removeCallbacks(current.timeout)
        if (current.bound) {
            current.bound = false
            runCatching { unbind(current) }
        }
    }

    fun retireBinding() = main.post {
        attempt?.also { attempt = null; release(it) }
    }

    override fun close() {
        closed = true
        retireBinding()
    }
}

internal fun acceptRootShell(shell: Shell): Boolean {
    if (shell.isRoot) return true
    // libsu caches a non-root fallback after denial; retire it so an explicit retry can ask again.
    runCatching { shell.close() }
    return false
}
