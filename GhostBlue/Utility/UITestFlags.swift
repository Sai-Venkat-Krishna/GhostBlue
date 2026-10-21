//
//  UITestFlags.swift
//  GhostBlue
//
//  Launch-argument hooks for automated UI verification (e.g.
//  `simctl launch <udid> <bundle> -startOnLocationTab`). Inert in normal
//  launches — the arguments are only present when explicitly passed.
//

import CoreGraphics
import Foundation

enum UITestFlags {
    /// Open the app on the Location tab instead of Home.
    static let startOnLocationTab =
        ProcessInfo.processInfo.arguments.contains("-startOnLocationTab")

    /// `-startTab home|location|energy|trips` opens that tab.
    static let startTab: String? = value(after: "-startTab")?.lowercased()

    /// `-layoutPreviewSize 466x678` lays the app out at that size in points
    /// and scales it onto the screen. simctl can't fold or unfold the iPhone
    /// Duo, and only one of its displays is on at a time, so this is how the
    /// other display's layout is checked. Debug builds only.
    static let layoutPreviewSize: CGSize? = {
        let parts = value(after: "-layoutPreviewSize")?.split(separator: "x").compactMap { Double($0) } ?? []
        guard parts.count == 2, parts[0] > 0, parts[1] > 0 else { return nil }
        return CGSize(width: parts[0], height: parts[1])
    }()

    private static func value(after flag: String) -> String? {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: flag), arguments.indices.contains(index + 1) else { return nil }
        return arguments[index + 1]
    }

    /// Present the trip planner sheet immediately.
    static let openRoutePlanner =
        ProcessInfo.processInfo.arguments.contains("-openRoutePlanner")
}
