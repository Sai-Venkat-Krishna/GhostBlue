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
        if settings.appTheme == .rivian {
            RivianLoader(size: size, lineWidth: max(3, size / 12))
        } else {
            ProgressView()
        }
    }
}
