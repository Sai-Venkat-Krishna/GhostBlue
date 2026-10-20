//
//  ViewExtensions.swift
//  GhostBlue
//
//  View extensions for consistent styling
//

import SwiftData
import SwiftUI

/// Renders its contents only while `model` is still a live, non-deleted SwiftData object.
/// Prevents the "model deleted while view is on screen" crash that fires when the user
/// pops back to a list and the vehicle has been removed in-flight (e.g. account refresh
/// drops a VIN). Falls back to an empty placeholder so the navigation pop animates
/// cleanly instead of leaving a half-rendered detail view.
struct PersistentModelGuard<Model: PersistentModel, Content: View>: View {
    let model: Model
    @ViewBuilder var content: () -> Content

    var body: some View {
        if model.modelContext != nil && !model.isDeleted {
            content()
        } else {
            Color.clear
        }
    }
}

/// A simple vertical line shape
struct Line: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.midX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.midX, y: rect.maxY))
        return path
    }
}

extension View {
    /// Applies consistent vehicle button card styling with rounded corners using iOS 26
    /// glassEffect — or a solid surface when `gbSolidCardColor` is set (Rivian theme).
    func vehicleCardGlassEffect(radius: CGFloat = 12.0) -> some View {
        modifier(VehicleCardSurface(radius: radius))
    }

    /// Stagger-in entrance: slides up 18pt and fades in with a spring, delayed by `delay` seconds.
    func cardEntry(visible: Bool, delay: Double) -> some View {
        self
            .offset(y: visible ? 0 : 18)
            .opacity(visible ? 1 : 0)
            .animation(
                .spring(response: 0.55, dampingFraction: 0.88).delay(delay),
                value: visible
            )
    }
}

// MARK: - Card surface style

private struct GBSolidCardColorKey: EnvironmentKey {
    static let defaultValue: Color? = nil
}

extension EnvironmentValues {
    /// When set (e.g. by the Rivian theme), card surfaces render as this solid colour
    /// instead of Liquid Glass. `nil` (the default) keeps the classic glass look.
    var gbSolidCardColor: Color? {
        get { self[GBSolidCardColorKey.self] }
        set { self[GBSolidCardColorKey.self] = newValue }
    }
}

private struct VehicleCardSurface: ViewModifier {
    let radius: CGFloat
    @Environment(\.gbSolidCardColor) private var solid

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: radius)
        if let solid {
            content
                .containerShape(shape)
                .background(solid, in: shape)
                .clipShape(shape)
        } else {
            content
                .containerShape(shape)
                .glassEffect(.regular.interactive(), in: shape)
                .clipShape(shape)
        }
    }
}
