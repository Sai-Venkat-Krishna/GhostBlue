//
//  MaterialTokens.swift
//  GhostBlue
//
//  Colour roles for the Material You theme, matching the Android app as it
//  runs: Material 3's tonal-spot scheme with Android's default wallpaper
//  colours (light values sampled from the Android app's Home on
//  2026-11-07; dark values are the same palettes' dark-scheme tones). iOS
//  doesn't let apps read the wallpaper, so the palette is fixed.
//

import SwiftUI

enum MDTokens {
    // MARK: Accent roles

    static let primary            = AppTheme.materialPrimary
    static let onPrimary          = Color(light: 0xFFFFFF, dark: 0x152E60)
    static let primaryContainer   = Color(light: 0xD9E2FF, dark: 0x2F4578)
    static let onPrimaryContainer = Color(light: 0x2F4578, dark: 0xD9E2FF)
    static let secondary          = Color(light: 0x575E71, dark: 0xC0C6DC)
    static let secondaryContainer = Color(light: 0xDCE2F9, dark: 0x404659)
    static let onSecondaryContainer = Color(light: 0x404659, dark: 0xDCE2F9)
    static let tertiary           = Color(light: 0x725572, dark: 0xE0BBDD)
    static let tertiaryContainer  = Color(light: 0xFCD7F9, dark: 0x593D59)

    // MARK: Surfaces

    static let background              = Color(light: 0xFAF8FF, dark: 0x121318)
    static let surface                 = background
    static let surfaceContainerLowest  = Color(light: 0xFFFFFF, dark: 0x0D0E13)
    static let surfaceContainerLow     = Color(light: 0xF4F3FA, dark: 0x1A1B20)
    static let surfaceContainer        = Color(light: 0xEEEDF4, dark: 0x1E1F25)
    static let surfaceContainerHigh    = Color(light: 0xE8E7EF, dark: 0x292A2F)
    static let surfaceContainerHighest = Color(light: 0xE2E2E9, dark: 0x33353A)

    // MARK: Ink and lines

    static let onSurface        = Color(light: 0x1A1B20, dark: 0xE2E2E9)
    static let onSurfaceVariant = Color(light: 0x44464F, dark: 0xC5C6D0)
    static let outline          = Color(light: 0x757780, dark: 0x8F9099)
    static let outlineVariant   = Color(light: 0xC5C6D0, dark: 0x44464F)
    static let error            = Color(light: 0xBA1A1A, dark: 0xFFB4AB)

    // MARK: Car colours (Android's `GhostBlueColors` for the Material theme)

    static let climate  = primary
    static let energy   = Color(light: 0x1B6D3A, dark: 0x6DD58C)
    static let location = tertiary
    static let service  = Color(light: 0x9A5200, dark: 0xFFB870)
    static let danger   = error

    // MARK: Shape (Material 3 Expressive)

    /// `shapes.extraLarge`, used by Android's cards.
    static let cardRadius: CGFloat = 28
    /// Smaller cards and tiles.
    static let tileRadius: CGFloat = 24
}
