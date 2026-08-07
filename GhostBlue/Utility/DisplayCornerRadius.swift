//
//  DisplayCornerRadius.swift
//  GhostBlue
//
//  Reads the device's hardware display corner radius so views that
//  hug the screen edges (the Apple-Maps-style persistent sheet) can
//  use a concentric corner radius — i.e. inner radius = outer radius
//  − inset, which makes the inner shape's curve visually parallel to
//  the screen's curve instead of looking off-center.
//
//  Apple doesn't expose this publicly, so we go through the same
//  private KVC key that Apple Maps and a handful of system apps use.
//  Falls back to 47pt (most notched/Dynamic-Island iPhones) when the
//  key is unavailable.
//

import UIKit

@MainActor
enum DisplayCornerRadius {
    static let value: CGFloat = {
        let key = "_displayCornerRadius"
        let screen: UIScreen? = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first?
            .screen
        let radius = screen?.value(forKey: key) as? CGFloat
        return radius ?? 47.0
    }()

    static func concentric(insetBy inset: CGFloat) -> CGFloat {
        max(8, value - inset)
    }
}
