//
//  AdaptiveLayout.swift
//  GhostBlue
//
//  Width- and height-based layout tiers. Screens switch on the space they
//  actually have rather than on device or size class, so the iPhone Duo's
//  inner display (669 × 951 pt) gets two-column layouts and its outer
//  display (466 × 678 pt) a compact, shorter one, whatever size class iOS
//  reports for either.
//

import SwiftUI

enum AdaptiveLayout {
    /// From this width a screen has room for two columns.
    static let wideWidth: CGFloat = 600
    /// Below this height (the Duo's outer display) fixed-height heroes shrink
    /// so the primary controls stay above the fold.
    static let shortHeight: CGFloat = 720

    static func isWide(_ width: CGFloat) -> Bool { width >= wideWidth }
    static func isShort(_ height: CGFloat) -> Bool { height > 0 && height < shortHeight }
}

extension View {
    /// Keeps `size` updated with the space this view is given.
    func readSize(into size: Binding<CGSize>) -> some View {
        onGeometryChange(for: CGSize.self) { $0.size } action: { size.wrappedValue = $0 }
    }
}

/// Stacks two groups vertically on compact widths and places them side by
/// side in equal, top-aligned columns on wide ones.
struct AdaptiveColumns<Leading: View, Trailing: View>: View {
    let isWide: Bool
    var spacing: CGFloat = 12
    @ViewBuilder var leading: Leading
    @ViewBuilder var trailing: Trailing

    var body: some View {
        if isWide {
            HStack(alignment: .top, spacing: spacing) {
                VStack(spacing: spacing) { leading }
                    .frame(maxWidth: .infinity)
                VStack(spacing: spacing) { trailing }
                    .frame(maxWidth: .infinity)
            }
        } else {
            VStack(spacing: spacing) {
                leading
                trailing
            }
        }
    }
}

#if DEBUG
/// Debug-only: lays `content` out at `size` and scales it to fit the screen.
/// Used to check one iPhone Duo display while the simulator shows the other
/// (`-layoutPreviewSize 466x678` for the outer display).
struct LayoutSizePreview<Content: View>: View {
    let size: CGSize
    @ViewBuilder var content: Content

    var body: some View {
        GeometryReader { proxy in
            let scale = min(proxy.size.width / size.width, proxy.size.height / size.height)
            content
                .ignoresSafeArea()
                .frame(width: size.width, height: size.height)
                .clipped()
                .scaleEffect(scale, anchor: .topLeading)
                .frame(width: size.width * scale, height: size.height * scale, alignment: .topLeading)
                .overlay {
                    Rectangle().strokeBorder(.red.opacity(0.6), lineWidth: 1)
                }
        }
    }
}
#endif
