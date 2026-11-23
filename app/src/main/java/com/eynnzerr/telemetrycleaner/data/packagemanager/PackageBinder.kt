package com.eynnzerr.telemetrycleaner.data.packagemanager

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import java.io.File
import java.io.IOException
import java.io.Reader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/** Calls the package service under Shizuku's identity using Android's Binder command protocol. */
internal object PackageBinder : PackageExecutor {
    // IBinder.SHELL_COMMAND_TRANSACTION is hidden from the public SDK. Its wire code is '_CMD'.
    private const val SHELL_COMMAND_TRANSACTION = 0x5f434d44
    private const val RESULT_TIMEOUT_SECONDS = 5L
    private const val OUTPUT_DRAIN_TIMEOUT_SECONDS = 5L
    private const val DUMP_TIMEOUT_SECONDS = 15L
    private const val COMMAND_TIMEOUT_SECONDS = 30L
    private const val MAX_OUTPUT_CHARS = 24 * 1024 * 1024

    /** Runs the fixed package arguments supplied by [PackageCommands] off the main thread. */
    override fun execute(args: List<String>): PackageShellResult {
        val result = CommandResult()
        val output = captureOutput(
            transaction = SHELL_COMMAND_TRANSACTION,
            timeoutSeconds = COMMAND_TIMEOUT_SECONDS,
        ) { descriptor, submit ->
            ParcelFileDescriptor.open(File("/dev/null"), ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                submit {
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
        }
        return PackageShellResult(result.awaitCode(), output)
    }

    /** Reads the requested records via the package service's dumpsys interface. */
    override fun dump(vararg args: String): PackageShellResult {
        val output = captureOutput(
            transaction = IBinder.DUMP_TRANSACTION,
            timeoutSeconds = DUMP_TIMEOUT_SECONDS,
        ) { descriptor, submit ->
            submit {
                writeFileDescriptor(descriptor.fileDescriptor)
                writeStringArray(args)
            }
        }
        return PackageShellResult(0, output)
    }

    /** Collects nearby system logs through the same Shizuku connection. */
    override fun systemLog(packageName: String, startedAt: Long): String =
        PackageDiagnostics.readShizukuSystemLog(packageName, startedAt)

    /**
     * Runs a synchronous Binder transaction on a daemon thread with a hard timeout while draining
     * the pipe concurrently. On timeout or interruption, closes the read end immediately so a slow
     * remote writer unblocks with EPIPE instead of holding server locks indefinitely.
     */
    private fun captureOutput(
        transaction: Int,
        timeoutSeconds: Long,
        prepareAndSubmit: (ParcelFileDescriptor, (Parcel.() -> Unit) -> Unit) -> Unit,
    ): String {
        val service = checkNotNull(SystemServiceHelper.getSystemService("package"))
        val binder = ShizukuBinderWrapper(service)
        return capturePipeOutput(timeoutSeconds = timeoutSeconds) { descriptor ->
            prepareAndSubmit(descriptor) { writeRequest ->
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeRequest()
                    binder.transact(transaction, data, reply, 0)
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }
        }
    }

    /**
     * Executes [runProducer] with the write end of an anonymous pipe on a daemon thread while
     * draining the read end up to [maxChars]. Enforces [timeoutSeconds] and immediately closes the
     * read end on failure or timeout so blocked writers fail fast with EPIPE.
     */
    internal fun capturePipeOutput(
        timeoutSeconds: Long,
        drainTimeoutSeconds: Long = OUTPUT_DRAIN_TIMEOUT_SECONDS,
        maxChars: Int = MAX_OUTPUT_CHARS,
        runProducer: (ParcelFileDescriptor) -> Unit,
    ): String {
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
        val output = FutureTask {
            ParcelFileDescriptor.AutoCloseInputStream(readEnd).bufferedReader().use { reader ->
                readBounded(reader, maxChars)
            }
        }
        val producerFailure = AtomicReference<Throwable?>()
        val producerDone = CountDownLatch(1)
        val producerThread = Thread({
            try {
                writeEnd.use { descriptor ->
                    runProducer(descriptor)
                }
            } catch (failure: Throwable) {
                producerFailure.set(failure)
            } finally {
                producerDone.countDown()
            }
        }, "package-binder-transact").apply {
            isDaemon = true
        }

        var producerStarted = false
        try {
            Thread(output, "package-binder-output").apply {
                isDaemon = true
                start()
            }
            producerThread.start()
            producerStarted = true

            val finished = try {
                producerDone.await(timeoutSeconds, TimeUnit.SECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
            if (!finished) {
                throw TimeoutException("Package service transaction timed out after ${timeoutSeconds}s.")
            }
            producerFailure.get()?.let { throw it }

            return try {
                output.get(drainTimeoutSeconds, TimeUnit.SECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            } catch (execution: ExecutionException) {
                throw execution.cause ?: execution
            }
        } finally {
            if (!producerStarted) {
                runCatching { writeEnd.close() }
            }
            producerThread.interrupt()
            output.cancel(true)
            runCatching { readEnd.close() }
        }
    }

    /** Reads characters from [reader] up to [maxChars], failing fast if output grows unbounded. */
    internal fun readBounded(reader: Reader, maxChars: Int): String {
        val buffer = CharArray(8_192)
        val builder = StringBuilder()
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) return builder.toString()
            if (builder.length + read > maxChars) {
                throw IOException("Package service output exceeded $maxChars characters.")
            }
            builder.append(buffer, 0, read)
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
