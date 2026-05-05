// swift-tools-version: 5.10

import PackageDescription

let package = Package(
    name: "MacOSHidInspector",
    platforms: [
        .macOS(.v13),
    ],
    products: [
        .executable(name: "bluetrack-hid-inspector", targets: ["MacOSHidInspector"]),
        .library(name: "BluetrackHostKit", targets: ["BluetrackHostKit"]),
    ],
    targets: [
        .target(
            name: "BluetrackHostKit"
        ),
        .executableTarget(
            name: "MacOSHidInspector",
            dependencies: ["BluetrackHostKit"],
            exclude: ["Info.plist"],
            linkerSettings: [
                .linkedFramework("IOKit"),
                .linkedFramework("CoreBluetooth"),
                // Embed Info.plist into the Mach-O so macOS TCC sees the
                // NSBluetoothAlwaysUsageDescription / NSInputMonitoringUsageDescription
                // purpose strings instead of aborting the process on first
                // privacy-protected API call. Required on macOS 26 and newer.
                .unsafeFlags([
                    "-Xlinker", "-sectcreate",
                    "-Xlinker", "__TEXT",
                    "-Xlinker", "__info_plist",
                    "-Xlinker", "Sources/MacOSHidInspector/Info.plist",
                ]),
            ]
        ),
    ]
)
