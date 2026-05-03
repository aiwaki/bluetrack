// swift-tools-version: 5.10

import PackageDescription

let package = Package(
    name: "MacOSHidInspector",
    platforms: [
        .macOS(.v13),
    ],
    products: [
        .executable(name: "bluetrack-hid-inspector", targets: ["MacOSHidInspector"]),
    ],
    targets: [
        .executableTarget(
            name: "MacOSHidInspector",
            linkerSettings: [
                .linkedFramework("IOKit"),
            ]
        ),
    ]
)
