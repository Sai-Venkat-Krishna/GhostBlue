//
//  UITestFlags.swift
//  GhostBlue
//
//  Launch-argument hooks for automated UI verification (e.g.
//  `simctl launch <udid> <bundle> -startOnLocationTab`). Inert in normal
//  launches — the arguments are only present when explicitly passed.
//

import Foundation

enum UITestFlags {
    /// Open the app on the Location tab instead of Home.
    static let startOnLocationTab =
        ProcessInfo.processInfo.arguments.contains("-startOnLocationTab")

    /// Present the trip planner sheet immediately.
    static let openRoutePlanner =
        ProcessInfo.processInfo.arguments.contains("-openRoutePlanner")
}
