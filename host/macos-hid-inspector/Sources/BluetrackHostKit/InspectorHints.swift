import Foundation

/// One row from the IOHID candidate list, shaped so the hint logic stays a
/// pure function with no IOKit dependency. The executable target builds
/// these from `IOHIDDevice` summaries before calling into here.
public struct CandidateProductPair: Equatable {
    public let product: String
    public let transport: String
    public let looksLikeGamepad: Bool

    public init(product: String, transport: String, looksLikeGamepad: Bool) {
        self.product = product
        self.transport = transport
        self.looksLikeGamepad = looksLikeGamepad
    }
}

public enum InspectorHints {
    /// When the current `nameFilter` produced zero matches but a Bluetooth-
    /// transport candidate with a non-empty product name is visible, return
    /// that name so the inspector can suggest `--name <result>` as a rerun.
    /// Returns nil when no clean recommendation is possible.
    ///
    /// This addresses the documented "phone-named composite" case: macOS
    /// bonds the device under one name (e.g. "Bluetrack") at the Bluetooth
    /// classic layer, but IOHID surfaces it under the phone's user-set
    /// product name (e.g. "aiwaki"). The default `--name Bluetrack` filter
    /// then misses the IOHID entry.
    public static func bestPhoneRename(
        currentNameFilter: String,
        candidates: [CandidateProductPair]
    ) -> String? {
        let normalizedFilter = currentNameFilter.lowercased()
        let usable = candidates.filter { candidate in
            !candidate.product.isEmpty &&
                candidate.transport.lowercased().contains("bluetooth") &&
                !candidate.product.lowercased().contains(normalizedFilter)
        }
        // Prefer gamepad-likes; among ties prefer names without parentheses
        // (the bare phone vs. accessories like "Magic Mouse (aiwaki)") and
        // then the shortest. Tiebreaker on alphabetical for determinism.
        let sorted = usable.sorted { a, b in
            if a.looksLikeGamepad != b.looksLikeGamepad {
                return a.looksLikeGamepad
            }
            let aHasParen = a.product.contains("(")
            let bHasParen = b.product.contains("(")
            if aHasParen != bHasParen {
                return !aHasParen
            }
            if a.product.count != b.product.count {
                return a.product.count < b.product.count
            }
            return a.product < b.product
        }
        return sorted.first?.product
    }

    /// When `companion` discovers the BLE peripheral first, cross-feed the
    /// peripheral name into the IOHID-side filter to remove the manual
    /// `--name <phone>` rerun the rename tip currently surfaces.
    ///
    /// Returns the override to apply, or nil if no change is appropriate:
    /// - nil/empty BLE name (peripheral has no advertised name) → nil
    /// - BLE name already substring-matches the current filter (e.g.
    ///   "Bluetrack Pro" against the default "Bluetrack") → nil, the existing
    ///   filter is already inclusive enough.
    ///
    /// Pure function with no IOKit/CoreBluetooth deps so it can live in tests.
    public static func bleNameToHidFilter(
        blePeripheralName: String?,
        currentFilter: String
    ) -> String? {
        guard let name = blePeripheralName else { return nil }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if trimmed.lowercased().contains(currentFilter.lowercased()) {
            return nil
        }
        return trimmed
    }
}
