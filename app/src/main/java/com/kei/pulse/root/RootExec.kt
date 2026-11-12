package com.kei.pulse.root

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.Parcel
import java.nio.charset.Charset

class RootExec {

    val pServerAvailable: Boolean = PServerBinder.get() != null

    fun executeAsRoot(cmd: String): Result<String?> {
        val binder = PServerBinder.get() ?: return Result.failure(IllegalStateException("PServer not available"))

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeStringArray(arrayOf(cmd, "1"))
            binder.transact(0, data, reply, 0)
            Result.success(decodeReply(reply))
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun decodeReply(reply: Parcel): String? {
        return reply.createByteArray()
            ?.toString(Charset.defaultCharset())
            ?.trim()
            ?.let { value -> if (value == "null") null else value }
    }
}

/**
 * Process-wide handle to the vendor `PServerBinder`. Looked up again whenever it is missing or dead:
 * `pservice` is an init service that restarts after a crash and registers a new binder.
 */
@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
private object PServerBinder {
    @Volatile
    private var cached: IBinder? = null

    fun get(): IBinder? = cached?.takeIf { it.isBinderAlive } ?: lookup()?.also { cached = it }

    private fun lookup(): IBinder? = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
        getService.invoke(serviceManager, "PServerBinder") as IBinder
    }.getOrNull()
}
