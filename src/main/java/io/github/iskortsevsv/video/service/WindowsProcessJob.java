package io.github.iskortsevsv.video.service;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.BaseTSD.SIZE_T;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/** Привязывает дочерние процессы Windows к времени жизни JVM. */
final class WindowsProcessJob {
    private static volatile HANDLE job;

    private WindowsProcessJob() {}

    interface Kernel extends StdCallLibrary {
        HANDLE CreateJobObjectW(Pointer attributes, WString name);

        boolean SetInformationJobObject(HANDLE job, int kind, Pointer info, int size);

        boolean AssignProcessToJobObject(HANDLE job, HANDLE process);

        HANDLE GetCurrentProcess();

        boolean CloseHandle(HANDLE handle);

        int GetLastError();
    }

    @Structure.FieldOrder({
        "perProcessUserTimeLimit",
        "perJobUserTimeLimit",
        "limitFlags",
        "minimumWorkingSetSize",
        "maximumWorkingSetSize",
        "activeProcessLimit",
        "affinity",
        "priorityClass",
        "schedulingClass"
    })
    public static class BasicLimits extends Structure {
        public long perProcessUserTimeLimit, perJobUserTimeLimit;
        public int limitFlags;
        public SIZE_T minimumWorkingSetSize = new SIZE_T(), maximumWorkingSetSize = new SIZE_T();
        public int activeProcessLimit;
        public SIZE_T affinity = new SIZE_T();
        public int priorityClass, schedulingClass;
    }

    @Structure.FieldOrder({
        "readOperations",
        "writeOperations",
        "otherOperations",
        "readBytes",
        "writeBytes",
        "otherBytes"
    })
    public static class IoCounters extends Structure {
        public long readOperations,
                writeOperations,
                otherOperations,
                readBytes,
                writeBytes,
                otherBytes;
    }

    @Structure.FieldOrder({
        "basic",
        "io",
        "processMemoryLimit",
        "jobMemoryLimit",
        "peakProcessMemoryUsed",
        "peakJobMemoryUsed"
    })
    public static class ExtendedLimits extends Structure {
        public BasicLimits basic = new BasicLimits();
        public IoCounters io = new IoCounters();
        public SIZE_T processMemoryLimit = new SIZE_T(), jobMemoryLimit = new SIZE_T();
        public SIZE_T peakProcessMemoryUsed = new SIZE_T(), peakJobMemoryUsed = new SIZE_T();
    }

    static synchronized void ensure() {
        if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows")
                || job != null) {
            return;
        }
        Kernel kernel = Native.load("kernel32", Kernel.class, W32APIOptions.DEFAULT_OPTIONS);
        HANDLE created = kernel.CreateJobObjectW(null, null);
        if (created == null) {
            throw new IllegalStateException(
                    "Cannot create Windows process job: " + kernel.GetLastError());
        }
        var limits = new ExtendedLimits();
        limits.basic.limitFlags = 0x00002000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        limits.write();
        if (!kernel.SetInformationJobObject(created, 9, limits.getPointer(), limits.size())
                || !kernel.AssignProcessToJobObject(created, kernel.GetCurrentProcess())) {
            int error = kernel.GetLastError();
            kernel.CloseHandle(created);
            throw new IllegalStateException(
                    "Cannot protect child processes with Windows job: " + error);
        }
        // Keep the only job handle open for the lifetime of the JVM. Windows closes it on exit,
        // including TerminateProcess, and terminates remaining members of the job.
        job = created;
    }
}
