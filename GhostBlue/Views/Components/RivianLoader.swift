//
//  RivianLoader.swift
//  GhostBlue
//
//  Original loading indicator for the Rivian theme: a rotating gold arc drawn
//  from scratch with Canvas + TimelineView (not derived from any third-party
//  animation file), plus a gold shimmer for skeleton placeholders.
//

import Foundation
import SwiftUI

/// A rotating arc "wheel" loader whose sweep breathes as it spins.
struct RivianLoader: View {
    var size: CGFloat = 48
    var color: Color = RivianPalette.gold
    var lineWidth: CGFloat = 4

    var body: some View {
        TimelineView(.animation) { timeline in
            let t = timeline.date.timeIntervalSinceReferenceDate
            let rotation = Angle(degrees: (t * 220).truncatingRemainder(dividingBy: 360))
            let sweep = 165.0 + 135.0 * sin(t * 1.6) // breathes ~30°..300°
            Canvas { ctx, sz in
                let rect = CGRect(origin: .zero, size: sz).insetBy(dx: lineWidth, dy: lineWidth)
                let center = CGPoint(x: rect.midX, y: rect.midY)
                let radius = min(rect.width, rect.height) / 2

                var track = Path()
                track.addArc(center: center, radius: radius,
                             startAngle: .degrees(0), endAngle: .degrees(360),
                             clockwise: false)
                ctx.stroke(track, with: .color(color.opacity(0.15)),
                           style: StrokeStyle(lineWidth: lineWidth))

                var arc = Path()
                arc.addArc(center: center, radius: radius,
                           startAngle: .degrees(0), endAngle: .degrees(sweep),
                           clockwise: false)
                ctx.stroke(arc, with: .color(color),
                           style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
            }
            .rotationEffect(rotation)
        }
        .frame(width: size, height: size)
        .accessibilityLabel(Text("Loading"))
    }
}

/// Moving-sheen shimmer for skeleton placeholders, themed gold. Clip the view
/// you apply it to (e.g. `.clipShape`) so the sheen stays within bounds.
private struct RivianShimmer: ViewModifier {
    var active: Bool
    @State private var phase: CGFloat = -1

    func body(content: Content) -> some View {
        content.overlay {
            if active {
                GeometryReader { geo in
                    let w = geo.size.width
                    LinearGradient(
                        colors: [.clear, RivianPalette.gold.opacity(0.35), .clear],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                    .frame(width: w * 0.6)
                    .offset(x: phase * w)
                    .onAppear {
                        withAnimation(.linear(duration: 1.3).repeatForever(autoreverses: false)) {
                            phase = 1.6
                        }
                    }
                }
                .allowsHitTesting(false)
            }
        }
    }
}

extension View {
    /// Overlay a gold shimmer sheen (for loading skeletons).
    func rivianShimmer(active: Bool = true) -> some View {
        modifier(RivianShimmer(active: active))
    }
}

#Preview {
    ZStack {
        LinearGradient(colors: [RivianPalette.bgTop, RivianPalette.bgBottom],
                       startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea()
        RivianLoader()
    }
}
