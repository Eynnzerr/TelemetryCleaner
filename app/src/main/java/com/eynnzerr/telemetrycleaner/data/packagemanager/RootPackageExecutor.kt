package com.eynnzerr.telemetrycleaner.data.packagemanager

import com.topjohnwu.superuser.Shell
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Runs fixed package manager commands in a verified, app-owned root shell.
 *
 * @property shell libsu's job runner for this specific su session.
 * @property process The underlying su process, retained for termination if a job hangs.
 */
internal class RootPackageExecutor private constructor(
    private val shell: Shell,
    private val process: Process,
) : PackageExecutor, Closeable {
    /** Prevents new commands as soon as the session is retired. */
    @Volatile
    private var closed = false

    /** Returns whether this verified session can still accept package commands. */
    fun isAlive(): Boolean = !closed && process.isAlive && shell.isRoot

    override fun execute(args: List<String>): PackageShellResult =
        runCommand(listOf("/system/bin/cmd", "package") + args)

    override fun dump(vararg args: String): PackageShellResult {
        val result = runCommand(listOf("/system/bin/dumpsys", "-t", "5", "package") + args)
        // dumpsys can write a partial record and still return zero after its own timeout.
        val timeout = result.output.lineSequence().firstOrNull {
            it.startsWith("*** SERVICE 'package' DUMP TIMEOUT")
        }
        return if (timeout == null) result else PackageShellResult(-1, timeout)
    }

    /** Retires this session immediately and releases its process and streams in the background. */
    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        // An active library job is not canceled by canceling its result future.
        Shell.EXECUTOR.execute {
            try {
                process.destroyForcibly()
            } finally {
                shell.close()
            }
        }
    }

    /** Executes one command off the main thread and expires an unresponsive session. */
    private fun runCommand(args: List<String>): PackageShellResult {
        val future = synchronized(this) {
            if (!isAlive()) return PackageShellResult(-1, "Error: Root session ended.")
            shell.newJob()
                .add(args.joinToString(" ", transform = ::quoteShellArgument))
                .to(mutableListOf(), mutableListOf())
                .enqueue()
        }
        val result = try {
            future.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            close()
            return PackageShellResult(-1, "Error: Root command timed out.")
        } catch (interrupted: InterruptedException) {
            close()
            throw interrupted
        }
        if (result.code == Shell.Result.JOB_NOT_EXECUTED) {
            close()
            return PackageShellResult(-1, "Error: Root session ended.")
        }
        return PackageShellResult(result.code, (result.out + result.err).joinToString("\n"))
    }

    /** Preserves one literal argument when sending a command to the shell. */
    private fun quoteShellArgument(argument: String): String = "'" + argument.replace("'", "'\\''") + "'"

    companion object {
        /** Gives the root manager time to respond to an interactive permission check. */
        private const val AUTHORIZATION_TIMEOUT_SECONDS = 30L

        /** Bounds individual package calls after the shell has been verified. */
        private const val COMMAND_TIMEOUT_SECONDS = 10L

        /** Opens a dedicated su shell and verifies UID 0 and package-service access on an I/O thread. */
        fun open(): RootPackageExecutor {
            val process = ProcessBuilder("su").start()
            val shell = try {
                Shell.Builder.create().setTimeout(AUTHORIZATION_TIMEOUT_SECONDS).build(process)
            } catch (failure: Exception) {
                runCatching { process.destroyForcibly() }
                throw failure
            }
            val executor = RootPackageExecutor(shell, process)
            try {
                val uid = executor.runCommand(listOf("/system/bin/id", "-u"))
                check(shell.isRoot && uid.exitCode == 0 && uid.output.trim() == "0") {
                    "Root manager did not grant UID 0."
                }
                val users = executor.execute(listOf("list", "users"))
                check(users.exitCode == 0 && users.output.lineSequence().any { it.trimStart().startsWith("UserInfo{") }) {
                    "Root shell cannot read package-manager users."
                }
                return executor
            } catch (failure: Exception) {
                executor.close()
                throw failure
            }
        }
    }
}
