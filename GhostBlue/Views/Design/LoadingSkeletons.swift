//
//  LoadingSkeletons.swift
//  GhostBlue
//
//  Loading placeholders shaped like the content they stand in for, with a
//  sheen that sweeps across them (gold under the Rivian theme), plus a
//  staged checklist for multi-step loads such as sign-in. Under Reduce
//  Motion the placeholders hold still.
//

import SwiftUI

// MARK: - Shimmer

private struct GBShimmerModifier: ViewModifier {
    var active: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var settings = AppSettings.shared

    private var highlight: Color {
        settings.appTheme == .rivian
            ? RivianPalette.gold.opacity(0.4)
            : Color.white.opacity(0.32)
    }

    func body(content: Content) -> some View {
        content
            .overlay {
                if active, !reduceMotion {
                    TimelineView(.animation) { context in
                        GeometryReader { geometry in
                            let width = geometry.size.width
                            let band = max(90, width * 0.55)
                            let phase = (context.date.timeIntervalSinceReferenceDate / 1.5)
                                .truncatingRemainder(dividingBy: 1)
                            LinearGradient(
                                colors: [.clear, highlight, .clear],
                                startPoint: .leading,
                                endPoint: .trailing
                            )
                            .frame(width: band, height: geometry.size.height)
                            .offset(x: -band + (width + band * 2) * phase)
                        }
                    }
                    .mask(content)
                    .allowsHitTesting(false)
                }
            }
    }
}

extension View {
    /// Sweeps a sheen across this view's opaque pixels, for loading
    /// placeholders. Off under Reduce Motion.
    func gbShimmer(active: Bool = true) -> some View {
        modifier(GBShimmerModifier(active: active))
    }
}

// MARK: - Primitives

/// One placeholder bar (or block, with a smaller corner radius).
struct GBSkeletonBar: View {
    var width: CGFloat?
    var height: CGFloat = 12
    var cornerRadius: CGFloat?

    var body: some View {
        RoundedRectangle(cornerRadius: cornerRadius ?? height / 2, style: .continuous)
            .fill(Color.primary.opacity(0.13))
            .frame(width: width, height: height)
    }
}

/// A placeholder bar sized as a fraction of the available width.
struct GBSkeletonFractionBar: View {
    var fraction: CGFloat
    var height: CGFloat = 12

    var body: some View {
        GeometryReader { geometry in
            GBSkeletonBar(width: geometry.size.width * fraction, height: height)
        }
        .frame(height: height)
    }
}

// MARK: - Cards

/// Generic content-card placeholder: a heading, a value and a detail row.
struct GBSkeletonCard: View {
    var height: CGFloat?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            GBSkeletonFractionBar(fraction: 0.45, height: 16)
            GBSkeletonFractionBar(fraction: 0.82, height: 26)
            HStack(spacing: 10) {
                GBSkeletonBar(width: 90, height: 12)
                GBSkeletonBar(width: 64, height: 12)
                Spacer(minLength: 0)
            }
        }
        .gbShimmer()
        .padding(18)
        .frame(maxWidth: .infinity, minHeight: height, alignment: .topLeading)
        .gbGlassCard()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Loading content")
    }
}

/// Placeholder for a Lock / Climate / Charging card.
struct GBCommandCardSkeleton: View {
    var body: some View {
        HStack(spacing: 14) {
            Circle()
                .fill(Color.primary.opacity(0.13))
                .frame(width: 34, height: 34)
                .frame(width: 52, height: 52)
            VStack(alignment: .leading, spacing: 7) {
                GBSkeletonBar(width: 92, height: 13)
                GBSkeletonBar(width: 132, height: 10)
            }
            Spacer(minLength: 0)
        }
        .gbShimmer()
        .padding(14)
        .frame(maxWidth: .infinity)
        .vehicleCardGlassEffect()
        .accessibilityHidden(true)
    }
}

/// Placeholder for a list row with a leading thumbnail (trips, sessions).
struct GBListRowSkeleton: View {
    var thumbnail: CGFloat = 64

    var body: some View {
        HStack(spacing: 14) {
            GBSkeletonBar(width: thumbnail, height: thumbnail, cornerRadius: 14)
            VStack(alignment: .leading, spacing: 9) {
                GBSkeletonFractionBar(fraction: 0.6, height: 14)
                GBSkeletonFractionBar(fraction: 0.85, height: 10)
                HStack(spacing: 8) {
                    GBSkeletonBar(width: 54, height: 18, cornerRadius: 9)
                    GBSkeletonBar(width: 54, height: 18, cornerRadius: 9)
                    Spacer(minLength: 0)
                }
            }
        }
        .gbShimmer()
        .padding(14)
        .frame(maxWidth: .infinity)
        .gbGlassCard(radius: GBDesign.tileRadius)
        .accessibilityHidden(true)
    }
}

/// The Home dashboard while its first status loads: the hero (render,
/// battery), the command cards and a row of stat tiles, in place.
struct GBHomeSkeleton: View {
    var body: some View {
        VStack(spacing: 12) {
            VStack(spacing: 16) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 7) {
                        GBSkeletonBar(width: 130, height: 16)
                        GBSkeletonBar(width: 90, height: 10)
                    }
                    Spacer()
                    GBSkeletonBar(width: 84, height: 28, cornerRadius: 14)
                }

                HStack(spacing: 14) {
                    Image(systemName: "car.side.fill")
                        .resizable()
                        .scaledToFit()
                        .foregroundStyle(Color.primary.opacity(0.1))
                        .frame(maxWidth: .infinity)
                        .frame(height: 96)
                    VStack(alignment: .trailing, spacing: 8) {
                        GBSkeletonBar(width: 72, height: 30, cornerRadius: 8)
                        GBSkeletonBar(width: 54, height: 11)
                    }
                    .frame(width: 104, alignment: .trailing)
                }
            }
            .gbShimmer()
            .padding(18)
            .frame(maxWidth: .infinity)
            .gbGlassCard(radius: GBDesign.heroRadius)

            GBCommandCardSkeleton()
            GBCommandCardSkeleton()

            HStack(spacing: 10) {
                ForEach(0..<2, id: \.self) { _ in
                    VStack(alignment: .leading, spacing: 10) {
                        GBSkeletonBar(width: 20, height: 20, cornerRadius: 6)
                        Spacer(minLength: 0)
                        GBSkeletonBar(width: 70, height: 16)
                        GBSkeletonBar(width: 48, height: 8)
                    }
                    .gbShimmer()
                    .padding(14)
                    .frame(maxWidth: .infinity, minHeight: 96, alignment: .leading)
                    .gbGlassCard(radius: GBDesign.tileRadius)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Loading vehicle status")
    }
}

// MARK: - Staged progress

/// A short checklist for loads made of known steps (signing in, loading
/// vehicles, reading status): done steps tick, the current one spins, the
/// rest wait. Shows what is actually happening instead of a bare spinner.
struct GBStagedProgress: View {
    let stages: [String]
    /// Index of the running stage; `stages.count` means all done.
    let current: Int

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var settings = AppSettings.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(Array(stages.enumerated()), id: \.offset) { index, title in
                HStack(spacing: 12) {
                    ZStack {
                        if index < current {
                            Image(systemName: "checkmark.circle.fill")
                                .font(.system(size: 20, weight: .semibold))
                                .foregroundStyle(.green)
                                .transition(reduceMotion ? .opacity : .scale.combined(with: .opacity))
                        } else if index == current {
                            BrandProgressView(size: 20)
                                .controlSize(.small)
                                .transition(.opacity)
                        } else {
                            Circle()
                                .stroke(Color.secondary.opacity(0.4), lineWidth: 1.5)
                                .frame(width: 18, height: 18)
                                .transition(.opacity)
                        }
                    }
                    .frame(width: 24, height: 24)

                    Text(title)
                        .font(.subheadline.weight(index == current ? .semibold : .regular))
                        .foregroundStyle(index <= current ? Color.primary : Color.secondary)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(title)
                .accessibilityValue(index < current ? "Done" : (index == current ? "In progress" : "Waiting"))
            }
        }
        .animation(
            reduceMotion ? .easeInOut(duration: 0.2) : .spring(response: 0.4, dampingFraction: 0.75),
            value: current
        )
    }
}
