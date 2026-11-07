//
//  RivianDesign.swift
//  GhostBlue
//
//  The Rivian-style design system used when the Rivian theme is active.
//  Adaptive colour tokens (light + dark, following system appearance like the
//  Rivian app), a type scale, metrics, and the reusable building blocks every
//  redesigned screen shares: backdrop, cards, section headers, list rows,
//  colour-coded tiles and circular command buttons.
//  All values are original — no Rivian assets.
//

import SwiftUI
import UIKit

enum RV {
    // MARK: Colour tokens (adaptive light / dark) — shared with the
    // widgets and Watch via Utility/RivianTokens.swift

    static let background   = RVTokens.background
    static let surface      = RVTokens.surface
    static let surfaceMuted = RVTokens.surfaceMuted
    static let ink          = RVTokens.ink
    static let inkSecondary = RVTokens.inkSecondary
    static let hairline     = RVTokens.hairline
    static let accent       = RVTokens.accent

    // Category colours (Rivian groups features by colour)
    static let climate       = RVTokens.climate
    static let energy        = RVTokens.energy
    static let vehicleCenter = RVTokens.vehicleCenter
    static let location      = RVTokens.location
    static let service       = RVTokens.service
    static let danger        = RVTokens.danger

    // Circular command buttons — Rivian marks the active state as solid ink.
    static let commandActiveFill   = RVTokens.commandActiveFill
    static let commandActiveIcon   = RVTokens.commandActiveIcon
    static let commandInactiveFill = RVTokens.commandInactiveFill
    static let commandInactiveIcon = RVTokens.commandInactiveIcon

    // MARK: Metrics

    enum Radius {
        static let hero: CGFloat = 28
        static let card: CGFloat = 24
        static let tile: CGFloat = 24
        static let row: CGFloat = 16
        static let chip: CGFloat = 10
    }

    enum Space {
        static let xs: CGFloat = 4
        static let s: CGFloat = 8
        static let m: CGFloat = 12
        static let l: CGFloat = 16
        static let xl: CGFloat = 20
        static let xxl: CGFloat = 28
    }

    // MARK: Type scale

    enum Font {
        static let display   = SwiftUI.Font.system(size: 34, weight: .bold, design: .rounded)
        static let title     = SwiftUI.Font.system(size: 28, weight: .bold, design: .rounded)
        static let headline  = SwiftUI.Font.system(size: 20, weight: .semibold, design: .rounded)
        static let tileTitle = SwiftUI.Font.system(size: 22, weight: .bold, design: .rounded)
        static let metric    = SwiftUI.Font.system(size: 44, weight: .bold, design: .rounded)
        static let body      = SwiftUI.Font.system(size: 16, weight: .regular)
        static let label     = SwiftUI.Font.system(size: 13, weight: .medium)
    }
}

// MARK: - Surfaces

extension View {
    /// Render GhostBlue card primitives (`vehicleCardGlassEffect`, `gbGlassCard`,
    /// `gbGlassCircle`) as solid Rivian surfaces inside this view.
    func rivianSurfaces() -> some View { environment(\.gbSolidCardColor, RV.surface) }
}

// MARK: - Components

/// Full-screen adaptive Rivian backdrop.
struct RVBackground: View {
    var body: some View { RV.background.ignoresSafeArea() }
}

/// Rounded card surface.
struct RVCard<Content: View>: View {
    var padding: CGFloat = RV.Space.l
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: RV.Radius.card, style: .continuous).fill(RV.surface))
    }
}

/// Small, letter-spaced section label.
struct RVSectionHeader: View {
    let title: String

    var body: some View {
        Text(title)
            .font(RV.Font.label)
            .foregroundStyle(RV.inkSecondary)
            .textCase(.uppercase)
            .tracking(0.6)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, RV.Space.xs)
            .accessibilityAddTraits(.isHeader)
    }
}

/// List row: colour-coded icon chip, title/subtitle, optional chevron.
struct RVRow: View {
    let title: String
    var subtitle: String?
    let systemImage: String
    var tint: Color = RV.accent
    var showsChevron = true

    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: RV.Radius.chip, style: .continuous).fill(tint)
                Image(systemName: systemImage)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.white)
            }
            .frame(width: 34, height: 34)
            .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.body.weight(.medium)).foregroundStyle(RV.ink)
                if let subtitle {
                    Text(subtitle).font(.footnote).foregroundStyle(RV.inkSecondary)
                }
            }
            Spacer(minLength: 0)
            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(RV.inkSecondary)
                    .accessibilityHidden(true)
            }
        }
        .contentShape(Rectangle())
    }
}

/// Colour-coded category tile (Climate / Energy / Vehicle Center / Location…).
struct RVTile: View {
    let title: String
    let systemImage: String
    let detail: String
    let color: Color
    let action: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 0) {
                Image(systemName: systemImage)
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(.white)
                Spacer(minLength: RV.Space.l)
                Text(title)
                    .font(RV.Font.tileTitle)
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
                Text(detail)
                    .font(.subheadline)
                    .foregroundStyle(.white.opacity(0.85))
                    .contentTransition(.numericText())
                    .animation(reduceMotion ? nil : .snappy, value: detail)
            }
            .frame(maxWidth: .infinity, minHeight: 124, alignment: .leading)
            .padding(18)
            .background(RoundedRectangle(cornerRadius: RV.Radius.tile, style: .continuous).fill(color))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}

/// Rivian circular command button (active = solid ink). When `command` is
/// set and its tag matches `commandTag`, the circle's outline shows that
/// command's progress (see `commandProgressBorder`).
struct RVCircleButton: View {
    let title: String
    let systemImage: String
    var active = false
    var dimmed = false
    var command: RemoteCommandTracker?
    var commandTag: String?
    let action: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var isCommandTarget: Bool {
        guard let command, let commandTag else { return false }
        return command.tag == commandTag
    }

    private var isPending: Bool { isCommandTarget && command?.isRunning == true }

    var body: some View {
        Button(action: action) {
            VStack(spacing: RV.Space.s) {
                Group {
                    if let command {
                        // Padded out so the ring traces just outside the circle.
                        circle
                            .padding(5)
                            .commandProgressBorder(
                                command,
                                shape: .circle,
                                tint: RV.accent,
                                successTint: RV.accent,
                                lineWidth: 3,
                                showsWash: false,
                                isActive: isCommandTarget
                            )
                            .padding(-5)
                    } else {
                        circle
                    }
                }
                .animation(
                    reduceMotion ? .easeInOut(duration: 0.2) : .spring(response: 0.45, dampingFraction: 0.8),
                    value: active
                )
                Text(title).font(RV.Font.label).foregroundStyle(RV.inkSecondary)
            }
        }
        .buttonStyle(.plain)
        .disabled(dimmed && !isPending)
        .opacity(dimmed && !active && !isPending ? 0.5 : 1)
        .accessibilityLabel(title)
        .accessibilityValue(isPending ? (command?.statusText ?? "") : "")
        .accessibilityAddTraits(active ? .isSelected : [])
    }

    private var circle: some View {
        ZStack {
            Circle().fill(active ? RV.commandActiveFill : RV.commandInactiveFill)
            Image(systemName: systemImage)
                .font(.system(size: 24, weight: .semibold))
                .foregroundStyle(active ? RV.commandActiveIcon : RV.commandInactiveIcon)
                .symbolEffect(.pulse, options: .repeating, isActive: isPending && !reduceMotion)
                .symbolEffect(
                    .bounce,
                    options: .nonRepeating,
                    value: reduceMotion || !isCommandTarget ? 0 : (command?.successCount ?? 0)
                )
        }
        .frame(width: 72, height: 72)
    }
}

// MARK: - Backdrop switch

private struct GBSolidBackdropKey: EnvironmentKey {
    static let defaultValue: Color? = nil
}

extension EnvironmentValues {
    /// Set per screen by `ghostBlueColorScheme()` under the Rivian and Material
    /// themes; makes `GBBackground` draw this solid page colour instead of the
    /// classic gradient.
    var gbSolidBackdrop: Color? {
        get { self[GBSolidBackdropKey.self] }
        set { self[GBSolidBackdropKey.self] = newValue }
    }
}

// MARK: - Settings rows

/// Coloured rounded-square icon used by Rivian-style settings rows.
struct SettingsIconChip: View {
    let systemImage: String
    let tint: Color

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: 30, height: 30)
            .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(tint))
            .accessibilityHidden(true)
    }
}

/// Settings row label. Under the Rivian theme: a coloured icon chip + title.
/// Under classic themes: exactly the original label — plain text, or a `Label`
/// when the row already had an icon (`classicShowsIcon`).
struct SettingsRowLabel: View {
    let title: String
    let systemImage: String
    var tint: Color = RV.accent
    var classicShowsIcon = false
    @State private var settings = AppSettings.shared

    var body: some View {
        if settings.appTheme == .rivian {
            HStack(spacing: 12) {
                SettingsIconChip(systemImage: systemImage, tint: tint)
                Text(LocalizedStringKey(title))
            }
        } else if settings.appTheme == .material {
            // Material list items lead with a plain icon in onSurfaceVariant.
            HStack(spacing: 16) {
                MaterialRowIcon(systemImage: systemImage)
                Text(LocalizedStringKey(title))
            }
        } else if classicShowsIcon {
            Label(LocalizedStringKey(title), systemImage: systemImage)
        } else {
            Text(LocalizedStringKey(title))
        }
    }
}

/// Prepends a Rivian icon chip to arbitrary row content under the Rivian theme;
/// classic themes render the content unchanged.
struct RivianRowIcon<Content: View>: View {
    let systemImage: String
    let tint: Color
    @ViewBuilder var content: Content
    @State private var settings = AppSettings.shared

    var body: some View {
        if settings.appTheme == .rivian {
            HStack(spacing: 12) {
                SettingsIconChip(systemImage: systemImage, tint: tint)
                content
            }
        } else if settings.appTheme == .material {
            HStack(spacing: 16) {
                MaterialRowIcon(systemImage: systemImage)
                content
            }
        } else {
            content
        }
    }
}

/// Leading icon of a Material list item.
struct MaterialRowIcon: View {
    let systemImage: String

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: 18, weight: .medium))
            .foregroundStyle(MDTokens.onSurfaceVariant)
            .frame(width: 24)
            .accessibilityHidden(true)
    }
}
