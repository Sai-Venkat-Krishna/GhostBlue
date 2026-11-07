//
//  LoadingOverlayView.swift
//  GhostBlue
//
//  Created by Mark Schmidt on 8/25/25.
//

import SwiftUI

/// The sign-in overlay on Add Account: which step is running (signing in,
/// then loading vehicles) instead of a bare spinner.
struct LoadingOverlayView: View {
    let brandName: String
    /// Index into `stages` of the running step.
    var stage = 0

    static let stages = ["Signing in", "Loading your vehicles"]

    var body: some View {
        ZStack {
            Color.black.opacity(0.3)
                .ignoresSafeArea()

            VStack(alignment: .leading, spacing: 18) {
                Text("Adding \(brandName) Account")
                    .font(.headline)
                    .fontWeight(.semibold)
                    .foregroundColor(.primary)

                GBStagedProgress(stages: Self.stages, current: stage)
            }
            .padding(24)
            .frame(minWidth: 260, alignment: .leading)
            .background {
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .fill(.regularMaterial)
                    .shadow(color: .black.opacity(0.12), radius: 24, x: 0, y: 12)
            }
            .padding(.horizontal, 40)
            .accessibilityElement(children: .contain)
            .accessibilityAddTraits(.isModal)
        }
    }
}
