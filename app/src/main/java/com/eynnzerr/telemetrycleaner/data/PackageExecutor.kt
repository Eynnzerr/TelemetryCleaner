package com.eynnzerr.telemetrycleaner.data

/** Output and completion status of a package manager request. */
internal data class PackageShellResult(val exitCode: Int, val output: String)

/** Executes package manager operations using one privileged connection. */
internal interface PackageExecutor {
    /** Whether the package command provides the preferred package dump for this connection. */
    val prefersShellDump: Boolean get() = false

    /** Runs fixed arguments through the package service's shell command interface. */
    fun execute(args: List<String>): PackageShellResult

    /** Reads one exact package through the package service's dump interface. */
    fun dump(packageName: String): PackageShellResult

    /** Reads related uninstall log entries when this connection offers that diagnostic. */
    fun systemLog(packageName: String, startedAt: Long): String? = null
}
