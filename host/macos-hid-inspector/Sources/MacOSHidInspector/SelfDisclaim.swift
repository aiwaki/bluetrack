import Darwin
import Foundation

/// Make this binary its own TCC "responsible process" so macOS evaluates the
/// embedded `Info.plist` (with `NSBluetoothAlwaysUsageDescription` /
/// `NSInputMonitoringUsageDescription`) instead of the parent app's plist.
///
/// On macOS 26 a privacy-protected API call (CoreBluetooth init,
/// `IOHIDDeviceOpen`, etc.) hard-aborts the process whenever the responsible
/// app does not declare a purpose string for the relevant TCC class. When the
/// inspector is launched from a third-party shell (Codex, Claude, IDE
/// terminals, etc.), the responsible process becomes that app, and the abort
/// fires before any of our code runs.
///
/// Calling `responsibility_spawnattrs_setdisclaim(&attrs, 1)` at posix_spawn
/// time tells the kernel that the spawned child should NOT inherit the
/// responsibility of its parent. The disclaimed child becomes responsible
/// for itself, and TCC reads the child's own Info.plist purpose strings.
///
/// We re-exec ourselves once with that flag set, propagate the child's exit
/// code, and stamp the env marker so the disclaimed copy skips this dance.
enum SelfDisclaim {
    static let envMarker = "BLUETRACK_TCC_DISCLAIMED"

    static func relaunchIfNeeded() {
        if ProcessInfo.processInfo.environment[envMarker] == "1" {
            return
        }
        guard let execPath = currentExecutablePath() else {
            return
        }
        guard let setDisclaim = loadSetDisclaim() else {
            return // SPI absent: fall through and let the original launch run.
        }

        var attrs: posix_spawnattr_t?
        if posix_spawnattr_init(&attrs) != 0 {
            return
        }
        defer { posix_spawnattr_destroy(&attrs) }

        if setDisclaim(&attrs, 1) != 0 {
            return
        }

        var argvBuf = CommandLine.arguments.map { strdup($0) }
        argvBuf.append(nil)
        defer {
            for ptr in argvBuf where ptr != nil {
                free(ptr)
            }
        }

        var env = ProcessInfo.processInfo.environment
        env[envMarker] = "1"
        var envBuf: [UnsafeMutablePointer<CChar>?] = env
            .sorted { $0.key < $1.key }
            .map { strdup("\($0.key)=\($0.value)") }
        envBuf.append(nil)
        defer {
            for ptr in envBuf where ptr != nil {
                free(ptr)
            }
        }

        var pid: pid_t = 0
        let rc = argvBuf.withUnsafeMutableBufferPointer { argvPtr in
            envBuf.withUnsafeMutableBufferPointer { envPtr in
                posix_spawn(&pid, execPath, nil, &attrs, argvPtr.baseAddress, envPtr.baseAddress)
            }
        }
        if rc != 0 {
            return
        }

        var status: Int32 = 0
        waitpid(pid, &status, 0)
        let exitCode: Int32 = if (status & 0x7F) == 0 {
            (status >> 8) & 0xFF
        } else {
            128 + (status & 0x7F)
        }
        exit(exitCode)
    }

    private static func currentExecutablePath() -> String? {
        var size: UInt32 = 0
        _ = _NSGetExecutablePath(nil, &size)
        guard size > 0 else { return nil }
        var buffer = [CChar](repeating: 0, count: Int(size))
        guard _NSGetExecutablePath(&buffer, &size) == 0 else { return nil }
        if let resolved = realpath(buffer, nil) {
            defer { free(resolved) }
            return String(cString: resolved)
        }
        return String(cString: buffer)
    }

    /// `responsibility_spawnattrs_setdisclaim` lives in libsystem_secinit.dylib
    /// (loaded as part of libSystem). Resolve it dynamically so a future
    /// macOS that drops the symbol degrades gracefully.
    private typealias SetDisclaimFn = @convention(c) (
        UnsafeMutablePointer<posix_spawnattr_t?>, Int32
    ) -> Int32

    private static func loadSetDisclaim() -> SetDisclaimFn? {
        guard let handle = dlopen(nil, RTLD_NOW),
              let sym = dlsym(handle, "responsibility_spawnattrs_setdisclaim") else
        {
            return nil
        }
        return unsafeBitCast(sym, to: SetDisclaimFn.self)
    }
}
