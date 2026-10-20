//
//  DesignSystem.swift
//  GhostBlue
//
//  Shared design language for the tabbed redesign: bold expressive
//  typography over deep gradients, with Liquid Glass surfaces for
//  every card and control.
//

import SwiftUI

// MARK: - Metrics

enum GBDesign {
    /// Corner radius for the large hero card at the top of a screen
    static let heroRadius: CGFloat = 28
    /// Corner radius for standard content cards
    static let cardRadius: CGFloat = 22
    /// Corner radius for small stat tiles
    static let tileRadius: CGFloat = 18
    /// Horizontal screen padding shared by all tab screens
    static let screenPadding: CGFloat = 16
}

// MARK: - Typography

extension Font {
    /// Giant hero number (battery percentage, efficiency)
    static let gbHero = Font.system(size: 64, weight: .heavy, design: .rounded)
    /// Unit that trails a hero number ("%", "mi/kWh")
    static let gbHeroUnit = Font.system(size: 24, weight: .bold, design: .rounded)
    /// Screen title (vehicle name, "Trips")
    static let gbDisplay = Font.system(size: 30, weight: .black, design: .rounded)
    /// Primary value inside a stat tile
    static let gbStatValue = Font.system(size: 19, weight: .bold, design: .rounded)
    /// Tiny uppercase label under a stat value
    static let gbStatLabel = Font.system(size: 10, weight: .semibold)
}

// MARK: - Glass Surfaces

extension View {
    /// Liquid Glass surface for cards and tiles in the redesigned screens.
    /// `interactive` adds the touch-responsive glass highlight for tappables.
    func gbGlassCard(radius: CGFloat = GBDesign.cardRadius, interactive: Bool = false) -> some View {
        modifier(GBAccessibleGlassModifier(radius: radius, interactive: interactive))
    }

    /// Circular Liquid Glass surface for small icon buttons.
    func gbGlassCircle() -> some View {
        modifier(GBAccessibleCircleModifier())
    }
}

private struct GBAccessibleGlassModifier: ViewModifier {
    let radius: CGFloat
    let interactive: Bool
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast
    @Environment(\.gbSolidCardColor) private var solid

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        Group {
            if let solid {
                content
                    .containerShape(shape)
                    .background(solid, in: shape)
            } else if reduceTransparency {
                content
                    .background(Color.black.opacity(0.86), in: shape)
            } else {
                content
                    .containerShape(shape)
                    .glassEffect(interactive ? .regular.interactive() : .regular, in: shape)
            }
        }
        .overlay {
            shape.stroke(
                Color.white.opacity(contrast == .increased ? 0.42 : 0.10),
                lineWidth: contrast == .increased ? 1.5 : 0.5
            )
        }
        .clipShape(shape)
    }
}

private struct GBAccessibleCircleModifier: ViewModifier {
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast
    @Environment(\.gbSolidCardColor) private var solid

    func body(content: Content) -> some View {
        Group {
            if let solid {
                content.background(solid, in: Circle())
            } else if reduceTransparency {
                content.background(Color.black.opacity(0.86), in: Circle())
            } else {
                content
                    .containerShape(Circle())
                    .glassEffect(.regular.interactive(), in: Circle())
            }
        }
        .overlay {
            Circle().stroke(
                Color.white.opacity(contrast == .increased ? 0.42 : 0.10),
                lineWidth: contrast == .increased ? 1.5 : 0.5
            )
        }
        .clipShape(Circle())
    }
}

struct GBSkeletonCard: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shimmer = false
    var height: CGFloat? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            skeletonLine(width: 0.45, height: 18)
            skeletonLine(width: 0.82, height: 28)
            HStack {
                skeletonLine(width: 0.34, height: 13)
                skeletonLine(width: 0.28, height: 13)
            }
        }
        .padding(18)
        .frame(minHeight: height)
        .background {
            LinearGradient(
                colors: [
                    Color.primary.opacity(0.06),
                    Color.primary.opacity(shimmer ? 0.18 : 0.08),
                    Color.primary.opacity(0.06)
                ],
                startPoint: shimmer ? .trailing : .leading,
                endPoint: shimmer ? .leading : .trailing
            )
        }
        .gbGlassCard()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Loading content")
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 1.1).repeatForever(autoreverses: true)) {
                shimmer = true
            }
        }
    }

    private func skeletonLine(width: CGFloat, height: CGFloat) -> some View {
        GeometryReader { geometry in
            Capsule()
                .fill(Color.primary.opacity(0.16))
                .frame(width: geometry.size.width * width, height: height)
        }
        .frame(height: height)
    }
}

// MARK: - Background

/// Full-screen gradient backdrop with a soft radial glow, shared by the
/// Home and Trips tabs. Colors come from the vehicle's background choice.
struct GBBackground: View {
    let colors: [Color]
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.gbRivianBackdrop) private var rivianBackdrop
    @State private var driftingGlow = false

    var body: some View {
        if rivianBackdrop {
            RV.background.ignoresSafeArea().allowsHitTesting(false)
        } else {
            classicBackdrop
        }
    }

    private var classicBackdrop: some View {
        LinearGradient(colors: colors, startPoint: .top, endPoint: .bottom)
            .overlay(
                RadialGradient(
                    colors: [Color.white.opacity(0.16), .clear],
                    center: driftingGlow ? .topTrailing : .topLeading,
                    startRadius: 0,
                    endRadius: 460
                )
            )
            .overlay(
                RadialGradient(
                    colors: [colors.last?.opacity(0.18) ?? .clear, .clear],
                    center: driftingGlow ? .bottomLeading : .bottomTrailing,
                    startRadius: 0,
                    endRadius: 380
                )
                // The translucent radial layer already adds depth; leaving
                // it in the normal compositing group avoids an Xcode 26 SDK
                // overload ambiguity around `blendMode(_:)`.
            )
            .ignoresSafeArea()
            .allowsHitTesting(false)
            .onAppear { startAmbientMotion() }
            .onChange(of: reduceMotion) { _, _ in startAmbientMotion() }
            .animation(
                reduceMotion ? nil : .easeInOut(duration: 11).repeatForever(autoreverses: true),
                value: driftingGlow
            )
    }

    private func startAmbientMotion() {
        driftingGlow = !reduceMotion
    }
}

extension Color {
    /// Perceived-luminance check used to pick a readable foreground scheme
    /// for content layered over a vehicle background gradient.
    var gbIsDark: Bool {
        let uiColor = UIColor(self)
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        guard uiColor.getRed(&red, green: &green, blue: &blue, alpha: &alpha) else {
            return false
        }
        let luminance = 0.299 * red + 0.587 * green + 0.114 * blue
        return luminance < 0.5
    }
}

extension BBVehicle {
    /// Foreground scheme that stays readable over `backgroundGradient`.
    var backgroundColorScheme: ColorScheme {
        (backgroundGradient.first?.gbIsDark ?? true) ? .dark : .light
    }
}

// MARK: - Section Header

/// Uppercase, letter-spaced label above a group of cards.
struct GBSectionHeader: View {
    let title: String

    var body: some View {
        Text(title.uppercased())
            .font(.system(size: 12, weight: .heavy))
            .tracking(2.2)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 6)
            .padding(.top, 8)
    }
}

// MARK: - Stat Tile

/// Small glass tile showing a single labelled statistic.
struct GBStatTile<Icon: View>: View {
    let label: String
    let value: String
    var tint: Color = .primary
    @ViewBuilder let icon: Icon
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var hasAppeared = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            icon
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(tint == .primary ? AnyShapeStyle(.secondary) : AnyShapeStyle(tint))

            Spacer(minLength: 0)

            Text(value)
                .font(.gbStatValue)
                .foregroundStyle(tint)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .contentTransition(.numericText())

            Text(label.uppercased())
                .font(.gbStatLabel)
                .tracking(1.2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .padding(14)
        .frame(maxWidth: .infinity, minHeight: 96, alignment: .leading)
        .gbGlassCard(radius: GBDesign.tileRadius)
        .scaleEffect(reduceMotion || hasAppeared ? 1 : 0.94)
        .opacity(hasAppeared ? 1 : 0)
        .animation(
            reduceMotion ? .default : .spring(response: 0.5, dampingFraction: 0.78),
            value: hasAppeared
        )
        .onAppear { hasAppeared = true }
    }
}

extension GBStatTile where Icon == Image {
    init(systemImage: String, label: String, value: String, tint: Color = .primary) {
        self.init(label: label, value: value, tint: tint) {
            Image(systemName: systemImage)
        }
    }
}

// MARK: - Battery Gauge

/// Bold capsule gauge for state of charge / fuel level, with an animated
/// sweep highlight while charging.
struct GBBatteryGauge: View {
    /// 0...100
    let percentage: Double
    var charging = false
    var tint: Color = .green
    /// Optional charge-limit marker, 0...100
    var targetPercentage: Double?

    @State private var sweepPhase = false

    private var clamped: Double { min(max(percentage, 0), 100) }

    var body: some View {
        GeometryReader { geometry in
            let fillWidth = max(14, geometry.size.width * clamped / 100)

            ZStack(alignment: .leading) {
                Capsule()
                    .fill(Color.primary.opacity(0.12))

                Capsule()
                    .fill(
                        LinearGradient(
                            colors: [tint.opacity(0.65), tint],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    )
                    .frame(width: fillWidth)
                    .overlay(alignment: .leading) {
                        if charging {
                            Capsule()
                                .fill(
                                    LinearGradient(
                                        colors: [.clear, .white.opacity(0.55), .clear],
                                        startPoint: .leading,
                                        endPoint: .trailing
                                    )
                                )
                                .frame(width: 46)
                                .offset(x: sweepPhase ? fillWidth : -46)
                                .animation(
                                    .easeInOut(duration: 1.6).repeatForever(autoreverses: false),
                                    value: sweepPhase
                                )
                        }
                    }
                    .clipShape(Capsule())
                    .animation(.spring(response: 0.8, dampingFraction: 0.9), value: clamped)

                if let target = targetPercentage, target > 0, target < 100 {
                    Rectangle()
                        .fill(Color.primary.opacity(0.55))
                        .frame(width: 2.5)
                        .offset(x: geometry.size.width * target / 100)
                }
            }
        }
        .frame(height: 14)
        .onAppear { sweepPhase = charging }
        .onChange(of: charging) { _, nowCharging in
            sweepPhase = nowCharging
        }
    }
}

// MARK: - Battery Color

/// Shared tint for state-of-charge displays.
func gbBatteryColor(percentage: Double, charging: Bool) -> Color {
    if charging { return .green }
    if percentage <= 20 { return .red }
    if percentage <= 40 { return .orange }
    return .green
}
