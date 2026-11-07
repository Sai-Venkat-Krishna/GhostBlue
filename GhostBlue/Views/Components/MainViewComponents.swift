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
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
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

                    Group {
                        if dynamicTypeSize.isAccessibilitySize {
                            VStack(spacing: 9) {
                                welcomeFeature("bolt.fill", "Controls")
                                welcomeFeature("chart.xyaxis.line", "Insights")
                                welcomeFeature("lock.shield.fill", "Private")
                            }
                        } else {
                            HStack(spacing: 9) {
                                welcomeFeature("bolt.fill", "Controls")
                                welcomeFeature("chart.xyaxis.line", "Insights")
                                welcomeFeature("lock.shield.fill", "Private")
                            }
                        }
                    }

                    Button {
                        showingAddAccount = true
                    } label: {
                        Label("Connect or try demo", systemImage: "plus.circle.fill")
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                    }
                    .gbProminentButtonStyle()
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
        .ghostBlueColorScheme()
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
        .background(
            AppSettings.shared.appTheme.solidCardColor.map { AnyShapeStyle($0) } ?? AnyShapeStyle(.thinMaterial),
            in: RoundedRectangle(
                cornerRadius: AppSettings.shared.appTheme == .material ? MDTokens.tileRadius : 16,
                style: .continuous
            )
        )
    }
}

struct EmptyVehiclesView: View {
    @Binding var isLoading: Bool
    @Binding var lastError: APIError?
    /// Index into `loadingStages` of the step that is running.
    var loadingStage = 0
    var onRetry: (() async -> Void)?

    static let loadingStages = ["Signing in", "Loading your vehicles", "Reading vehicle status"]

    var body: some View {
        ZStack {
            GBBackground(colors: AppSettings.shared.themeColors)
            if isLoading {
                garageLoading
                    .transition(.opacity)
            } else {
                emptyOrError
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.3), value: isLoading)
        .ghostBlueColorScheme()
    }

    /// The first load: the steps it is working through, over placeholders
    /// shaped like the dashboard that is about to appear.
    private var garageLoading: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Loading your garage")
                .font(.gbDisplay)
                .padding(.top, 24)

            GBStagedProgress(stages: Self.loadingStages, current: loadingStage)
                .padding(18)
                .frame(maxWidth: .infinity, alignment: .leading)
                .gbGlassCard(radius: GBDesign.tileRadius)

            GBCommandCardSkeleton()
            GBCommandCardSkeleton()
            Spacer(minLength: 0)
        }
        .padding(.horizontal, GBDesign.screenPadding)
        .frame(maxWidth: 520)
    }

    private var emptyOrError: some View {
        VStack(spacing: 20) {
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
                .gbProminentButtonStyle()
                .disabled(isLoading)
            }
        }
        .padding()
    }
}
