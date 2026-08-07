//
//  AnimationExtensions.swift
//  GhostBlue
//
//  Created by Mark Schmidt on 9/6/25.
//

import SwiftUI

private enum AnimationKind {
    case spin
    case pulse(range: ClosedRange<Double>)

    var range: ClosedRange<Double> {
        switch self {
        case .spin: 0.0 ... 360.0
        case let .pulse(range): range
        }
    }

    func animation(duration: Double) -> Animation {
        switch self {
        case .spin: .linear(duration: duration).repeatForever(autoreverses: false)
        case .pulse: .easeInOut(duration: duration).repeatForever(autoreverses: true)
        }
    }
}

private struct SingleAnimationModifier: ViewModifier {
    let kind: AnimationKind
    let duration: Double
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(kind: AnimationKind, duration: Double) {
        self.kind = kind
        self.duration = duration
        _animatedValue = State(initialValue: kind.range.lowerBound)
    }

    @State private var animatedValue: Double

    func body(content: Content) -> some View {
        Group {
            switch kind {
            case .spin:
                content.rotationEffect(.degrees(animatedValue))
            case .pulse:
                content.scaleEffect(animatedValue)
            }
        }
        .onAppear { startAnimation() }
        .onChange(of: reduceMotion) { _, _ in startAnimation() }
        .animation(reduceMotion ? nil : animation, value: animatedValue)
    }

    private var animation: Animation {
        kind.animation(duration: duration)
    }

    private func startAnimation() {
        guard !reduceMotion else {
            animatedValue = kind.range.lowerBound
            return
        }
        DispatchQueue.main.async {
            animatedValue = kind.range.upperBound
        }
    }
}

extension View {
    @ViewBuilder
    func spin(_ active: Bool, duration: Double = 2.0) -> some View {
        if active {
            modifier(SingleAnimationModifier(kind: .spin, duration: duration))
        } else {
            self
        }
    }

    @ViewBuilder
    func pulse(_ active: Bool, duration: Double = 2.0) -> some View {
        if active {
            modifier(SingleAnimationModifier(kind: .pulse(range: 1.0 ... 1.2), duration: duration))
        } else {
            self
        }
    }

    func bouncePress() -> some View {
        modifier(BouncePressModifier())
    }
}

private struct BouncePressModifier: ViewModifier {
    @GestureState private var isPressed = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .scaleEffect(reduceMotion || !isPressed ? 1.0 : 0.965)
            .brightness(reduceMotion || !isPressed ? 0 : 0.035)
            .animation(
                reduceMotion ? .default : .spring(response: 0.2, dampingFraction: 0.62),
                value: isPressed
            )
            .simultaneousGesture(
                DragGesture(minimumDistance: 0)
                    .updating($isPressed) { _, state, _ in state = true }
            )
    }
}
