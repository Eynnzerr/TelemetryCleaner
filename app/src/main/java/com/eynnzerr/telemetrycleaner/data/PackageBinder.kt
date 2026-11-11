package com.eynnzerr.telemetrycleaner.data

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/** Output and completion status of a package manager request. */
internal data class PackageShellResult(val exitCode: Int, val output: String)

/** Calls the package service under Shizuku's identity using Android's Binder command protocol. */
internal object PackageBinder {
    // IBinder.SHELL_COMMAND_TRANSACTION is hidden from the public SDK. Its wire code is '_CMD'.
    private const val SHELL_COMMAND_TRANSACTION = 0x5f434d44
    private const val RESULT_TIMEOUT_SECONDS = 5L

    /** Runs the fixed package arguments supplied by [PackageCommands] off the main thread. */
    fun execute(args: List<String>): PackageShellResult {
        val result = CommandResult()
        val output = ParcelFileDescriptor.open(File("/dev/null"), ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
            captureOutput(SHELL_COMMAND_TRANSACTION) { descriptor ->
                // Match BinderProxy.shellCommand: stdin, stdout, stderr, arguments,
                // a nullable ShellCallback binder, then a ResultReceiver.
                writeFileDescriptor(input.fileDescriptor)
                writeFileDescriptor(descriptor.fileDescriptor)
                writeFileDescriptor(descriptor.fileDescriptor)
                writeStringArray(args.toTypedArray())
                writeStrongBinder(null)
                result.writeToParcel(this, 0)
            }
        }
        return PackageShellResult(result.awaitCode(), output)
    }

    /** Reads the exact package via the package service's dumpsys interface. */
    fun dump(packageName: String): PackageShellResult {
        val output = captureOutput(IBinder.DUMP_TRANSACTION) { descriptor ->
            writeFileDescriptor(descriptor.fileDescriptor)
            writeStringArray(arrayOf(packageName))
        }
        return PackageShellResult(0, output)
    }

    /** Drains the pipe during the synchronous transaction so large dumps cannot block Binder. */
    private fun captureOutput(
        transaction: Int,
        writeRequest: Parcel.(ParcelFileDescriptor) -> Unit,
    ): String {
        val service = checkNotNull(SystemServiceHelper.getSystemService("package"))
        val binder = ShizukuBinderWrapper(service)
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
        val output = FutureTask {
            ParcelFileDescriptor.AutoCloseInputStream(readEnd).bufferedReader().use { it.readText() }
        }
        try {
            writeEnd.use { descriptor ->
                Thread(output, "package-binder-output").apply {
                    isDaemon = true
                    start()
                }
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeRequest(descriptor)
                    binder.transact(transaction, data, reply, 0)
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }
            return output.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            output.cancel(true)
            readEnd.close()
        }
    }

    /** Receives the package command's exit status independently of the Binder reply. */
    private class CommandResult : ResultReceiver(null) {
        private val completed = CountDownLatch(1)
        private var exitCode = -1

        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            exitCode = resultCode
            completed.countDown()
        }

        /** Returns a failure status if the package manager did not report completion. */
        fun awaitCode(): Int =
            if (completed.await(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) exitCode else -1
    }
}
