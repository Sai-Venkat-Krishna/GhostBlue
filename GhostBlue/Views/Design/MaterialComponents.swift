//
//  MaterialComponents.swift
//  GhostBlue
//
//  Material 3 Expressive building blocks for the Material You theme, drawn to
//  match the Android app's Compose components: cards, card headers, stat
//  tiles, shaped icons, filled / tonal / toggle / split buttons with the
//  expressive press shape, the linear (and wavy) progress indicator, and the
//  morphing loading indicator. Colours come from `MDTokens`.
//

import SwiftUI

// MARK: - Shapes

/// Material's "cookie" shape: a circle with soft scalloped lobes, as
/// `MaterialShapes.Cookie6Sided` / `Cookie9Sided`.
struct MDCookieShape: Shape {
    var lobes: Int = 6
    /// How deep the scallops are, as a fraction of the radius.
    var depth: CGFloat = 0.08
    var rotation: Angle = .zero

    func path(in rect: CGRect) -> Path {
        let center = CGPoint(x: rect.midX, y: rect.midY)
        let radius = min(rect.width, rect.height) / 2
        var path = Path()
        let steps = 180
        for step in 0...steps {
            let theta = Double(step) / Double(steps) * 2 * .pi
            let r = radius * (1 - depth + depth * CGFloat(cos(Double(lobes) * (theta + rotation.radians))))
            let point = CGPoint(
                x: center.x + r * CGFloat(cos(theta - .pi / 2)),
                y: center.y + r * CGFloat(sin(theta - .pi / 2))
            )
            if step == 0 { path.move(to: point) } else { path.addLine(to: point) }
        }
        path.closeSubpath()
        return path
    }
}

/// An icon on a tinted cookie, as Android's `ShapedIcon` in the Controls card.
struct MDShapedIcon: View {
    let systemImage: String
    var size: CGFloat = 40
    var tint: Color = MDTokens.primary

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: size * 0.45, weight: .semibold))
            .foregroundStyle(tint)
            .frame(width: size, height: size)
            .background(tint.opacity(0.16), in: MDCookieShape())
            .contentTransition(.symbolEffect(.replace))
            .accessibilityHidden(true)
    }
}

// MARK: - Cards

/// Android's `SectionCard`: a 28 pt card with 12 pt between its rows.
struct MDSectionCard<Content: View>: View {
    var container: Color = MDTokens.surfaceContainer
    var padding: CGFloat = 16
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            content
        }
        .padding(padding)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(container, in: RoundedRectangle(cornerRadius: MDTokens.cardRadius, style: .continuous))
    }
}

/// Android's `CardHeader`: a title, an optional line under it, and an
/// optional trailing control.
struct MDCardHeader<Trailing: View>: View {
    let title: String
    var subtitle: String?
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(MDType.titleMedium)
                    .foregroundStyle(MDTokens.onSurface)
                if let subtitle {
                    Text(subtitle)
                        .font(MDType.bodySmall)
                        .foregroundStyle(MDTokens.onSurfaceVariant)
                        .contentTransition(.opacity)
                }
            }
            Spacer(minLength: 8)
            trailing
        }
        .accessibilityElement(children: .combine)
    }
}

extension MDCardHeader where Trailing == EmptyView {
    init(title: String, subtitle: String? = nil) {
        self.init(title: title, subtitle: subtitle) { EmptyView() }
    }
}

/// Android's `StatTile` / `StatGrid`: labelled values, two per row.
struct MDStatGrid: View {
    let items: [(label: String, value: String)]
    var columns = 2

    var body: some View {
        VStack(spacing: 12) {
            ForEach(Array(stride(from: 0, to: items.count, by: columns)), id: \.self) { start in
                HStack(alignment: .top, spacing: 12) {
                    ForEach(start..<min(start + columns, items.count), id: \.self) { index in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(items[index].label)
                                .font(MDType.labelMedium)
                                .foregroundStyle(MDTokens.onSurfaceVariant)
                            Text(items[index].value)
                                .font(MDType.statValue)
                                .foregroundStyle(MDTokens.onSurface)
                                .lineLimit(1)
                                .minimumScaleFactor(0.7)
                                .contentTransition(.numericText())
                        }
                        .padding(.vertical, 4)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityElement(children: .combine)
                    }
                    if items.count - start < columns {
                        ForEach(0..<(columns - (items.count - start)), id: \.self) { _ in
                            Spacer().frame(maxWidth: .infinity)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Type scale

/// Android's Material type roles at the sizes the Android app renders.
enum MDType {
    /// The large top app bar title on Energy and Trips.
    static let pageTitle   = Font.system(size: 34, weight: .regular)
    static let topBarTitle = Font.system(size: 28, weight: .semibold)
    static let display     = Font.system(size: 54, weight: .regular)
    static let titleMedium = Font.system(size: 18, weight: .semibold)
    static let titleSmall  = Font.system(size: 16, weight: .semibold)
    static let statValue   = Font.system(size: 22, weight: .bold)
    static let bodyLarge   = Font.system(size: 16, weight: .medium)
    static let bodyMedium  = Font.system(size: 15)
    static let bodySmall   = Font.system(size: 14)
    static let labelLarge  = Font.system(size: 14, weight: .medium)
    static let labelMedium = Font.system(size: 13, weight: .medium)
    static let labelSmall  = Font.system(size: 12, weight: .medium)
}

// MARK: - Buttons

/// How a Material button is filled.
enum MDButtonKind {
    /// Primary fill (`Button`).
    case filled
    /// Secondary container (`FilledTonalButton`).
    case tonal
    /// A toggle that isn't checked: its own container on the card.
    case toggleOff
}

/// Material 3 Expressive button: pill-shaped (or with the given corners),
/// squaring off a little while pressed, as `ButtonDefaults.shapes()`.
struct MDButtonStyle: ButtonStyle {
    var kind: MDButtonKind = .filled
    /// Corners: leading-top, leading-bottom, trailing-bottom, trailing-top.
    /// `nil` means fully round.
    var corners: RectangleCornerRadii?
    var horizontalPadding: CGFloat = 20
    static let touchPadding: CGFloat = 4
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(MDType.labelLarge)
            .lineLimit(1)
            .foregroundStyle(foreground)
            .padding(.horizontal, horizontalPadding)
            .frame(minHeight: 40)
            .background {
                GeometryReader { geometry in
                    let full = geometry.size.height / 2
                    let pressedRadius: CGFloat = 12
                    let radii = corners ?? RectangleCornerRadii(
                        topLeading: full, bottomLeading: full, bottomTrailing: full, topTrailing: full
                    )
                    UnevenRoundedRectangle(
                        cornerRadii: configuration.isPressed
                            ? RectangleCornerRadii(
                                topLeading: min(radii.topLeading, pressedRadius),
                                bottomLeading: min(radii.bottomLeading, pressedRadius),
                                bottomTrailing: min(radii.bottomTrailing, pressedRadius),
                                topTrailing: min(radii.topTrailing, pressedRadius)
                            )
                            : radii,
                        style: .continuous
                    )
                    .fill(background)
                }
            }
            // A 40 pt button in a 48 pt touch target, as Compose does.
            .padding(.vertical, MDButtonStyle.touchPadding)
            .contentShape(Rectangle())
            .animation(
                reduceMotion ? nil : .spring(response: 0.22, dampingFraction: 0.6),
                value: configuration.isPressed
            )
    }

    private var background: Color {
        guard isEnabled else { return MDTokens.onSurface.opacity(0.1) }
        switch kind {
        case .filled: return MDTokens.primary
        case .tonal: return MDTokens.secondaryContainer
        case .toggleOff: return MDTokens.surfaceContainerHighest
        }
    }

    private var foreground: Color {
        guard isEnabled else { return MDTokens.onSurface.opacity(0.38) }
        switch kind {
        case .filled: return MDTokens.onPrimary
        case .tonal: return MDTokens.onSecondaryContainer
        case .toggleOff: return MDTokens.onSurfaceVariant
        }
    }
}

extension RectangleCornerRadii {
    /// A connected button group's leading button: round outside, tight inside.
    static func mdLeading(height: CGFloat = 48, inner: CGFloat = 8) -> RectangleCornerRadii {
        RectangleCornerRadii(topLeading: height / 2, bottomLeading: height / 2, bottomTrailing: inner, topTrailing: inner)
    }

    static func mdTrailing(height: CGFloat = 48, inner: CGFloat = 8) -> RectangleCornerRadii {
        RectangleCornerRadii(topLeading: inner, bottomLeading: inner, bottomTrailing: height / 2, topTrailing: height / 2)
    }

    static func mdMiddle(inner: CGFloat = 8) -> RectangleCornerRadii {
        RectangleCornerRadii(topLeading: inner, bottomLeading: inner, bottomTrailing: inner, topTrailing: inner)
    }
}

/// A connected group of toggle buttons with one checked, as Android's
/// `ToggleButton` rows (Energy's range, Trips' date filter).
struct MDToggleGroup<Option: Hashable>: View {
    let options: [Option]
    @Binding var selection: Option
    let title: (Option) -> String

    var body: some View {
        HStack(spacing: 2) {
            ForEach(Array(options.enumerated()), id: \.element) { index, option in
                let checked = option == selection
                Button {
                    selection = option
                } label: {
                    Text(title(option))
                        .frame(maxWidth: .infinity)
                        .minimumScaleFactor(0.8)
                }
                .buttonStyle(
                    MDButtonStyle(
                        kind: checked ? .filled : .toggleOff,
                        corners: checked ? nil : corners(at: index),
                        horizontalPadding: 8
                    )
                )
                .accessibilityAddTraits(checked ? .isSelected : [])
            }
        }
        .animation(.spring(response: 0.32, dampingFraction: 0.8), value: selection)
    }

    private func corners(at index: Int) -> RectangleCornerRadii {
        if index == 0 { return .mdLeading() }
        if index == options.count - 1 { return .mdTrailing() }
        return .mdMiddle()
    }
}

extension View {
    /// `.borderedProminent`, with Material's onPrimary label under that
    /// theme: iOS draws white, unreadable on the light dark-mode primary.
    @MainActor @ViewBuilder
    func gbProminentButtonStyle() -> some View {
        if AppSettings.shared.appTheme == .material {
            buttonStyle(.borderedProminent).foregroundStyle(MDTokens.onPrimary)
        } else {
            buttonStyle(.borderedProminent)
        }
    }
}

/// A 40 pt round tonal icon button in a 48 pt touch target (`FilledTonalIconButton`).
struct MDTonalIconButton: View {
    let systemImage: String
    let accessibilityLabel: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(MDTokens.onSecondaryContainer)
                .frame(width: 40, height: 40)
                .background(MDTokens.secondaryContainer, in: Circle())
                .padding(MDButtonStyle.touchPadding)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .bouncePress()
        .accessibilityLabel(accessibilityLabel)
    }
}

/// A plain 48 pt icon button, as in Android's top app bar.
struct MDIconButton<Label: View>: View {
    let accessibilityLabel: String
    let action: () -> Void
    @ViewBuilder var label: Label

    var body: some View {
        Button(action: action) {
            label
                .font(.system(size: 20, weight: .medium))
                .foregroundStyle(MDTokens.onSurfaceVariant)
                .frame(width: 48, height: 48)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel)
    }
}

// MARK: - Progress

/// Material 3 Expressive linear progress: an indicator, a gap, the track in
/// secondaryContainer and a stop dot at the end. Wavy while `wavy` (Android's
/// `LinearWavyProgressIndicator`, used while charging).
struct MDLinearProgress: View {
    let progress: Double
    var tint: Color = MDTokens.primary
    var wavy = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let showsWave = wavy && !reduceMotion
        TimelineView(.animation(paused: !showsWave)) { context in
            GeometryReader { geometry in
                let width = geometry.size.width
                let height = geometry.size.height
                let clamped = min(max(progress, 0), 1)
                let filled = width * clamped
                let gap: CGFloat = clamped > 0 && clamped < 1 ? 4 : 0
                let phase = context.date.timeIntervalSinceReferenceDate * 2.4

                ZStack(alignment: .leading) {
                    if clamped < 1 {
                        Capsule()
                            .fill(MDTokens.secondaryContainer)
                            .frame(width: max(0, width - filled - gap), height: 4)
                            .offset(x: filled + gap)
                        Circle()
                            .fill(tint)
                            .frame(width: 4, height: 4)
                            .offset(x: width - 4)
                    }
                    if showsWave {
                        MDWave(phase: phase)
                            .stroke(tint, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                            .frame(width: filled, height: height)
                    } else {
                        Capsule()
                            .fill(tint)
                            .frame(width: filled, height: 4)
                    }
                }
                .frame(height: height)
            }
        }
        .frame(height: wavy ? 12 : 4)
        .animation(reduceMotion ? nil : .spring(response: 0.8, dampingFraction: 0.9), value: progress)
        .accessibilityElement()
        .accessibilityLabel("Battery")
        .accessibilityValue("\(Int((min(max(progress, 0), 1) * 100).rounded())) percent")
    }
}

private struct MDWave: Shape {
    var phase: Double

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let amplitude = rect.height / 2 - 2
        let wavelength: CGFloat = 24
        let inset: CGFloat = 2
        guard rect.width > inset * 2 else { return path }
        var x = inset
        path.move(to: CGPoint(x: x, y: rect.midY + amplitude * CGFloat(sin(phase))))
        while x <= rect.width - inset {
            let y = rect.midY + amplitude * CGFloat(sin(Double(x / wavelength) * 2 * .pi - phase))
            path.addLine(to: CGPoint(x: x, y: y))
            x += 1
        }
        return path
    }
}

/// Material 3 Expressive's `LoadingIndicator`: a primary shape that turns
/// and morphs its lobes. Under Reduce Motion it holds still.
struct MDLoadingIndicator: View {
    var size: CGFloat = 32
    var tint: Color = MDTokens.primary
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(paused: reduceMotion)) { context in
            let t = reduceMotion ? 0 : context.date.timeIntervalSinceReferenceDate
            MDCookieShape(
                lobes: 7,
                depth: 0.06 + 0.08 * CGFloat(0.5 + 0.5 * sin(t * 3)),
                rotation: .radians(t * 1.6)
            )
            .fill(tint)
            .frame(width: size * 0.78, height: size * 0.78)
            .frame(width: size, height: size)
        }
        .accessibilityLabel("Loading")
    }
}
