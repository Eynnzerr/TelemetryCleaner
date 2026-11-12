package com.eynnzerr.telemetrycleaner.shell;

import android.annotation.SuppressLint;
import android.os.Process;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Deletes one package for one Android user as the ADB shell user.
 *
 * <p>Telemetry Cleaner starts this class through {@code app_process} when a ROM refuses the
 * package command layer. The shell identity can still call the hidden
 * {@code IPackageManager.deletePackageAsUser} interface directly, which skips checks that ROMs
 * add around {@code pm}. Arguments are the package name and the Android user ID.
 */
public final class PackageUninstaller {
    private static final int SHELL_UID = 2000;

    private PackageUninstaller() {
    }

    /** Entry point for app_process; prints markers that the caller recognizes. */
    @SuppressLint("PrivateApi")
    public static void main(String[] args) {
        try {
            if (Process.myUid() != SHELL_UID) {
                System.out.println("SKIP_NOT_SHELL uid=" + Process.myUid());
                return;
            }
            if (args.length < 2) {
                System.out.println("USAGE: " + PackageUninstaller.class.getName()
                        + " <package> <userId>");
                return;
            }
            String packageName = args[0];
            int userId = Integer.parseInt(args[1]);

            Object packageManager = Class.forName("android.app.ActivityThread")
                    .getMethod("getPackageManager")
                    .invoke(null);
            Class<?> packageManagerInterface = Class.forName("android.content.pm.IPackageManager");
            Class<?> publicPackageManager = Class.forName("android.content.pm.PackageManager");
            // Delete the system copy for this user without keeping the app's data, matching the
            // data-clearing behavior of the primary uninstall command.
            int flags = publicPackageManager.getField("DELETE_SYSTEM_APP").getInt(null);
            Method deletePackageAsUser = packageManagerInterface.getMethod(
                    "deletePackageAsUser",
                    String.class,
                    int.class,
                    Class.forName("android.content.pm.IPackageDeleteObserver"),
                    int.class,
                    int.class);
            try {
                deletePackageAsUser.invoke(packageManager, packageName, -1, null, userId, flags);
                System.out.println("DELETE_REQUESTED " + packageName + " user=" + userId
                        + " flags=" + flags);
            } catch (InvocationTargetException e) {
                System.out.println("DELETE_FAILED " + e.getCause());
            }
        } catch (Throwable t) {
            System.out.println("ERROR " + t);
        }
    }
}
