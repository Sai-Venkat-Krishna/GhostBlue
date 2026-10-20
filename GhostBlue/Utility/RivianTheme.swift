//
//  RivianTheme.swift
//  GhostBlue
//
//  "Rivian" theme styling — an original motion + colour layer added as a new
//  app theme. Everything here (palette, motion curves, loader geometry) is
//  defined from scratch and inspired by the calm, deliberate feel of adventure
//  EV companion apps; no third-party asset files are used. Selected via
//  Settings → Theme → "Rivian".
//

import SwiftUI

/// Fixed colours for the Rivian theme: warm near-black surfaces with a single
/// confident amber-gold accent — a deliberate contrast to the cooler neon
/// defaults of the other themes.
enum RivianPalette {
    /// Signature accent. Warm amber-gold (~#FFAC00). Canonical value lives on
    /// `AppTheme.rivianGold` so multi-target code can resolve it.
    static let gold = AppTheme.rivianGold
    static let goldSoft = Color(red: 1.0, green: 0.78, blue: 0.28)
    /// Background gradient stops — charcoal drifting to a faint warm tint.
    static let bgTop = Color(red: 0.04, green: 0.04, blue: 0.045)
    static let bgBottom = Color(red: 0.11, green: 0.09, blue: 0.055)
    static let surface = Color(red: 0.08, green: 0.08, blue: 0.085)
    static let hairline = Color.white.opacity(0.12)
}

/// Which motion personality a theme uses.
enum ThemeMotionStyle {
    case standard
    case rivian
}

/// A bundle of animation "personality" for a theme. Views read this from the
/// environment (`@Environment(\.themeMotion)`) and use it for their transitions,
/// so switching theme restyles motion wherever call sites adopt it — without a
/// per-screen rewrite.
struct ThemeMotion {
    var style: ThemeMotionStyle
    /// Everyday state changes (toggles, value updates).
    var primary: Animation
    /// Larger "hero" changes (sheet present, screen swap).
    var emphasis: Animation
    /// Quick feedback (button press, selection).
    var snappy: Animation
    /// How far a pressable element scales on touch-down (1.0 = none).
    var pressScale: CGFloat

    /// Transition for content entering/leaving.
    var contentTransition: AnyTransition {
        switch style {
        case .standard:
            return .opacity
        case .rivian:
            return .asymmetric(
                insertion: .move(edge: .trailing).combined(with: .opacity),
                removal: .opacity
            )
        }
    }

    /// The app's existing feel — snappy springs, Material-ish timing.
    static let standard = ThemeMotion(
        style: .standard,
        primary: .spring(response: 0.35, dampingFraction: 0.90),
        emphasis: .spring(response: 0.45, dampingFraction: 0.85),
        snappy: .spring(response: 0.25, dampingFraction: 0.80),
        pressScale: 0.97
    )

    /// Rivian feel — slower, settled, confident: gentle overshoot on hero moves,
    /// smooth deceleration, a soft slide+fade for content.
    static let rivian = ThemeMotion(
        style: .rivian,
        primary: .spring(response: 0.55, dampingFraction: 0.92),
        emphasis: .spring(response: 0.70, dampingFraction: 0.80),
        snappy: .spring(response: 0.40, dampingFraction: 0.86),
        pressScale: 0.94
    )
}

private struct ThemeMotionKey: EnvironmentKey {
    static let defaultValue: ThemeMotion = .standard
}

extension EnvironmentValues {
    var themeMotion: ThemeMotion {
        get { self[ThemeMotionKey.self] }
        set { self[ThemeMotionKey.self] = newValue }
    }
}

extension View {
    /// Inject a theme's motion personality into the environment.
    func themeMotion(_ motion: ThemeMotion) -> some View {
        environment(\.themeMotion, motion)
    }
}

/// Press-scale button style that reads the active theme's `pressScale` and
/// `snappy` animation, so buttons feel like the current theme.
struct ThemedPressButtonStyle: ButtonStyle {
    @Environment(\.themeMotion) private var motion
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? motion.pressScale : 1.0)
            .animation(motion.snappy, value: configuration.isPressed)
    }
}

extension ButtonStyle where Self == ThemedPressButtonStyle {
    static var themedPress: ThemedPressButtonStyle { ThemedPressButtonStyle() }
}

/// Applies the Rivian screen treatment — gold tint, following system appearance
/// (light or dark) like the Rivian app — when the Rivian theme is active, and is a
/// no-op under every other theme. Lets any screen adopt the Rivian look with a
/// single `.rivianScreen()`.
struct RivianScreenStyle: ViewModifier {
    @State private var settings = AppSettings.shared
    func body(content: Content) -> some View {
        if settings.appTheme == .rivian {
            content
                .tint(RivianPalette.gold)
        } else {
            content
        }
    }
}

extension View {
    /// Adopt the Rivian theme (gold, system light/dark) on this screen when active.
    func rivianScreen() -> some View { modifier(RivianScreenStyle()) }
}

/// Screens pushed from Rivian-styled menus (Service, Settings) use the Rivian
/// page colour instead of the system grouped grey. No-op for classic themes.
struct RivianListBackground: ViewModifier {
    @State private var settings = AppSettings.shared
    func body(content: Content) -> some View {
        if settings.appTheme == .rivian {
            content
                .scrollContentBackground(.hidden)
                .background(RVBackground())
        } else {
            content
        }
    }
}

extension View {
    func rivianListBackground() -> some View { modifier(RivianListBackground()) }
}

/// Tab screens with a custom header instead of a navigation bar: under the
/// Rivian theme, back the status bar with the page colour so scrolled content
/// doesn't run under the clock and Dynamic Island. No-op for classic themes.
struct RivianStatusBarBackdrop: ViewModifier {
    @State private var settings = AppSettings.shared
    func body(content: Content) -> some View {
        if settings.appTheme == .rivian {
            content.safeAreaInset(edge: .top, spacing: 0) {
                Color.clear
                    .frame(height: 0)
                    .background(RV.background.ignoresSafeArea(edges: .top))
            }
        } else {
            content
        }
    }
}

extension View {
    func rivianStatusBarBackdrop() -> some View { modifier(RivianStatusBarBackdrop()) }
}

/// GhostBlue screens force a dark colour scheme for their classic look. Under the
/// Rivian theme they instead follow system appearance (light or dark), like the
/// Rivian app. Use in place of `.environment(\.colorScheme, .dark)`.
struct GhostBlueSchemeModifier: ViewModifier {
    /// Scheme used under classic (non-Rivian) themes.
    var classic: ColorScheme? = .dark
    @Environment(\.colorScheme) private var inherited
    @State private var settings = AppSettings.shared
    func body(content: Content) -> some View {
        let isRivian = settings.appTheme == .rivian
        content
            .environment(\.colorScheme, isRivian ? inherited : (classic ?? inherited))
            .environment(\.gbSolidCardColor, isRivian ? RV.surface : nil)
            .environment(\.gbRivianBackdrop, isRivian)
    }
}

extension View {
    /// Dark for classic GhostBlue themes; system light/dark under the Rivian theme.
    func ghostBlueColorScheme(classic: ColorScheme? = .dark) -> some View {
        modifier(GhostBlueSchemeModifier(classic: classic))
    }
}
