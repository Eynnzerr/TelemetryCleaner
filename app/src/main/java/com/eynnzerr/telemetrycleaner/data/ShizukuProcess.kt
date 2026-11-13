package com.eynnzerr.telemetrycleaner.data

import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

/** Starts a remote process under the connected Shizuku server's identity. */
internal fun startShizukuProcess(command: Array<String>): ShizukuRemoteProcess {
    // Shizuku 13 made newProcess private. The release rules preserve the method for reflection.
    val method = Shizuku::class.java.getDeclaredMethod(
        "newProcess",
        Array<String>::class.java,
        Array<String>::class.java,
        String::class.java,
    )
    method.isAccessible = true
    return method.invoke(null, command, null, null) as ShizukuRemoteProcess
}
