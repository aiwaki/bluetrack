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
            linkerSettings: [
                .linkedFramework("IOKit"),
                .linkedFramework("CoreBluetooth"),
            ]
        ),
    ]
)
