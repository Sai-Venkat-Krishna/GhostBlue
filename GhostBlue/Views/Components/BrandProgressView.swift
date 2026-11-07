//
//  BrandProgressView.swift
//  GhostBlue
//
//  Theme-aware loading indicator. Renders the gold `RivianLoader` when the
//  Rivian theme is active, and the standard `ProgressView` otherwise — a
//  drop-in replacement for `ProgressView()` so every indeterminate spinner in
//  the app follows the selected theme's motion.
//

import SwiftUI

struct BrandProgressView: View {
    var size: CGFloat = 28
    @State private var settings = AppSettings.shared

    var body: some View {
        switch settings.appTheme {
        case .rivian:
            RivianLoader(size: size, lineWidth: max(3, size / 12))
        case .material:
            // Material's indeterminate circular indicator: a primary arc that
            // grows and shrinks as it turns.
            RivianLoader(size: size, color: MDTokens.primary, lineWidth: max(2.5, size / 10))
        default:
            ProgressView()
        }
    }
}
