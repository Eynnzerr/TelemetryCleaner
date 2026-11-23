package com.eynnzerr.telemetrycleaner.data.packagemanager

/** Output and completion status of a package manager request. */
internal data class PackageShellResult(val exitCode: Int, val output: String)

/** Executes package manager operations using one privileged connection. */
internal interface PackageExecutor {
    /** Runs fixed arguments through the package service's shell command interface. */
    fun execute(args: List<String>): PackageShellResult

    /** Reads package records through the package service's dump interface with literal arguments. */
    fun dump(vararg args: String): PackageShellResult

    /** Reads related uninstall log entries when this connection offers that diagnostic. */
    fun systemLog(packageName: String, startedAt: Long): String? = null
}
