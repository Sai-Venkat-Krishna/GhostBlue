//
//  MainViewComponents.swift
//  GhostBlue
//
//  Created by Mark Schmidt on 8/25/25.
//

import BetterBlueKit
import SwiftUI

struct EmptyAccountsView: View {
    let transition: Namespace.ID
    @State private var showingAddAccount = false
    @State private var showingTroubleshooting = false

    var body: some View {
        ZStack {
            GBBackground(colors: AppSettings.shared.themeColors)

            ScrollView {
                VStack(spacing: 22) {
                    ZStack {
                        Circle()
                            .fill(AppSettings.shared.accentColor.opacity(0.14))
                            .frame(width: 150, height: 150)
                            .blur(radius: 1)
                        Image(systemName: "car.side.fill")
                            .font(.system(size: 72, weight: .medium))
                            .foregroundStyle(
                                LinearGradient(
                                    colors: [.white, AppSettings.shared.accentColor],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                )
                            )
                    }

                    VStack(spacing: 8) {
                        Text("Your smarter garage")
                            .font(.largeTitle.bold())
                            .multilineTextAlignment(.center)
                        Text("Connect a vehicle or explore every feature safely with the private Fake demo.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: 330)
                    }

                    HStack(spacing: 9) {
                        welcomeFeature("bolt.fill", "Controls")
                        welcomeFeature("chart.xyaxis.line", "Insights")
                        welcomeFeature("lock.shield.fill", "Private")
                    }

                    Button {
                        showingAddAccount = true
                    } label: {
                        Label("Connect or try demo", systemImage: "plus.circle.fill")
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .tint(AppSettings.shared.accentColor)
                    .matchedTransitionSource(
                        id: "add-account",
                        in: transition,
                    )

                    Button {
                        showingTroubleshooting = true
                    } label: {
                        Label("Trouble signing in?", systemImage: "questionmark.circle")
                            .font(.callout)
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(AppSettings.shared.accentColor)
                }
                .padding(.horizontal, 28)
                .padding(.top, 100)
                .padding(.bottom, 40)
            }
        }
        .environment(\.colorScheme, .dark)
        .sheet(isPresented: $showingAddAccount) {
            NavigationView {
                AddAccountView()
                    .toolbar {
                        ToolbarItem(
                            placement: .topBarLeading,
                            content: {
                                Button {
                                    showingAddAccount = false
                                } label: {
                                    Text("Cancel")
                                }
                            },
                        )
                    }
            }
            .navigationTransition(
                .zoom(sourceID: "add-account", in: transition),
            )
        }
        .sheet(isPresented: $showingTroubleshooting) {
            NavigationStack {
                TroubleshootingView()
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) {
                            Button("Done") { showingTroubleshooting = false }
                        }
                    }
            }
        }
    }

    private func welcomeFeature(_ icon: String, _ title: String) -> some View {
        VStack(spacing: 7) {
            Image(systemName: icon)
                .foregroundStyle(AppSettings.shared.accentColor)
            Text(title)
                .font(.caption.bold())
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 13)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
    }
}

struct EmptyVehiclesView: View {
    @Binding var isLoading: Bool
    @Binding var lastError: APIError?
    var onRetry: (() async -> Void)?

    var body: some View {
        ZStack {
            GBBackground(colors: AppSettings.shared.themeColors)
            VStack(spacing: 20) {
                if isLoading {
                    ProgressView()
                        .scaleEffect(1.5)
                        .tint(AppSettings.shared.accentColor)
                    Text("Loading your garage…")
                        .foregroundColor(.secondary)
                } else {
                    Image(
                        systemName: lastError != nil
                            ? "exclamationmark.triangle.fill"
                            : "car.side.fill"
                    )
                    .font(.system(size: 64))
                    .foregroundStyle(
                        lastError != nil ? Color.orange : AppSettings.shared.accentColor
                    )

                    Text(lastError != nil ? "Couldn’t refresh" : "No vehicles found")
                        .font(.title.bold())
                }

                if let error = lastError {
                    ErrorDetailsView(
                        error: ActionError(action: "Load vehicles", error: error)
                    )
                    .padding(.horizontal)

                    Button("Try Again") {
                        lastError = nil
                        Task {
                            await onRetry?()
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(isLoading)
                }
            }
            .padding()
        }
        .environment(\.colorScheme, .dark)
    }
}
