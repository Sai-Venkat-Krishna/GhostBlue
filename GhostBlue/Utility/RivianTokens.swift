//
//  RivianTokens.swift
//  GhostBlue
//
//  Colour tokens for the Rivian theme, shared by the app, the widgets and
//  Live Activity, and the Watch app and complications. The app's design
//  system (`RV` in Views/Design/RivianDesign.swift) builds on these; the
//  extensions use them directly because they can't see the app's view files.
//  All values are original — no Rivian assets.
//

import SwiftUI
#if canImport(UIKit)
    import UIKit
#endif

enum RVTokens {
    // MARK: Surfaces and ink (adaptive light / dark)

    static let background   = Color(light: 0xF4F4F2, dark: 0x0B0B0C)
    static let surface      = Color(light: 0xFFFFFF, dark: 0x18181A)
    static let surfaceMuted = Color(light: 0xEBEBE8, dark: 0x232326)
    static let ink          = Color(light: 0x111113, dark: 0xF2F2F0)
    static let inkSecondary = Color(light: 0x6B6B72, dark: 0x9C9CA3)
    static let hairline     = Color(lightAlpha: (0, 0, 0, 0.08), darkAlpha: (1, 1, 1, 0.10))
    static let accent       = AppTheme.rivianGold

    // MARK: Category colours (Rivian groups features by colour)

    static let climate       = Color(light: 0x2A6BED, dark: 0x3D7BFF)
    static let energy        = Color(light: 0x2EB35C, dark: 0x34C468)
    static let vehicleCenter = Color(light: 0x29292E, dark: 0x636366)
    static let location      = Color(light: 0x6E57D1, dark: 0x8069E6)
    static let service       = Color(light: 0xE07B1A, dark: 0xF08C2B)
    static let danger        = Color(light: 0xD93A3A, dark: 0xFF5A5A)

    // MARK: Circular command buttons — the active state is solid ink

    static let commandActiveFill   = Color(light: 0x111113, dark: 0xF2F2F0)
    static let commandActiveIcon   = Color(light: 0xFFFFFF, dark: 0x111113)
    static let commandInactiveFill = Color(light: 0xE6E6E3, dark: 0x2A2A2D)
    static let commandInactiveIcon = Color(light: 0x111113, dark: 0xF2F2F0)

    /// Fixed (non-adaptive) surface and ink for a known appearance. For
    /// system-drawn backgrounds such as a Live Activity's tint, which iOS
    /// may resolve against a different appearance than the content.
    static func surface(for scheme: ColorScheme) -> Color {
        Color(rvHex: scheme == .dark ? 0x18181A : 0xFFFFFF)
    }

    static func ink(for scheme: ColorScheme) -> Color {
        Color(rvHex: scheme == .dark ? 0xF2F2F0 : 0x111113)
    }

    /// Battery fill: green, turning red when the pack is low.
    static func battery(_ percentage: Double?) -> Color {
        guard let percentage else { return inkSecondary }
        return percentage <= 20 ? danger : energy
    }
}

// MARK: - Adaptive colour helpers

extension Color {
    /// Adaptive colour from two `0xRRGGBB` values. watchOS is always dark and
    /// has no dynamic colours, so it uses the dark value.
    init(light: UInt32, dark: UInt32) {
        #if os(watchOS)
            self.init(rvHex: dark)
        #else
            self.init(uiColor: UIColor { traits in
                UIColor(rvHex: traits.userInterfaceStyle == .dark ? dark : light)
            })
        #endif
    }

    /// Adaptive colour from RGBA tuples (0…1).
    init(lightAlpha l: (CGFloat, CGFloat, CGFloat, CGFloat),
         darkAlpha d: (CGFloat, CGFloat, CGFloat, CGFloat)) {
        #if os(watchOS)
            self.init(.sRGB, red: d.0, green: d.1, blue: d.2, opacity: d.3)
        #else
            self.init(uiColor: UIColor { traits in
                let c = traits.userInterfaceStyle == .dark ? d : l
                return UIColor(red: c.0, green: c.1, blue: c.2, alpha: c.3)
            })
        #endif
    }

    fileprivate init(rvHex: UInt32) {
        self.init(.sRGB,
                  red: Double((rvHex >> 16) & 0xFF) / 255,
                  green: Double((rvHex >> 8) & 0xFF) / 255,
                  blue: Double(rvHex & 0xFF) / 255,
                  opacity: 1)
    }
}

#if !os(watchOS)
    private extension UIColor {
        convenience init(rvHex: UInt32) {
            self.init(red: CGFloat((rvHex >> 16) & 0xFF) / 255,
                      green: CGFloat((rvHex >> 8) & 0xFF) / 255,
                      blue: CGFloat(rvHex & 0xFF) / 255,
                      alpha: 1)
        }
    }
#endif
