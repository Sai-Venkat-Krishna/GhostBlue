//
//  ABRPSettingsSection.swift
//  GhostBlue
//
//  ABRP (Iternio) configuration: API keys + telemetry toggle for the
//  Settings screen, and the per-vehicle car model picker used from
//  VehicleInfoView.
//

import BetterBlueKit
import SwiftData
import SwiftUI

struct ABRPSettingsSection: View {
    @State private var appSettings = AppSettings.shared

    var body: some View {
        Section {
            SecureField("Plan API Key", text: $appSettings.abrpPlanApiKey)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            SecureField("Telemetry API Key", text: $appSettings.abrpTelemetryApiKey)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Toggle("Send Live Data to ABRP", isOn: $appSettings.abrpTelemetryEnabled)
        } header: {
            Text("ABRP Route Planning")
        } footer: {
            Text("""
            Trip planning uses the ABRP (Iternio) plan API — request an API key by \
            emailing contact@iternio.com. Telemetry-only keys are free and let GhostBlue \
            send live battery data to your ABRP account. Each vehicle also needs its own \
            Live Data token (ABRP app > Settings > Live Data) and car model, set in the \
            vehicle's settings.
            """)
            .font(.caption)
            .foregroundColor(.secondary)
        }
    }
}

// MARK: - Car Model Picker

/// Searchable list of ABRP car model typecodes; selection is stored on the
/// vehicle as `abrpCarModel`. The list endpoint works without an API key.
struct ABRPCarModelPicker: View {
    let bbVehicle: BBVehicle
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss

    @State private var models: [ABRPCarModel] = []
    @State private var searchText = ""
    @State private var isLoading = true
    @State private var loadError: String?

    private var filteredModels: [ABRPCarModel] {
        guard !searchText.isEmpty else { return models }
        return models.filter {
            $0.displayName.localizedCaseInsensitiveContains(searchText) ||
                $0.typecode.localizedCaseInsensitiveContains(searchText)
        }
    }

    var body: some View {
        Group {
            if isLoading {
                ProgressView("Loading car models…")
            } else if let loadError {
                ContentUnavailableView {
                    Label("Couldn't Load Car Models", systemImage: "wifi.exclamationmark")
                } description: {
                    Text(loadError)
                } actions: {
                    Button("Retry") {
                        Task { await load() }
                    }
                }
            } else {
                List(filteredModels) { model in
                    Button {
                        bbVehicle.abrpCarModel = model.typecode
                        try? modelContext.save()
                        dismiss()
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(model.displayName)
                                    .foregroundColor(.primary)
                                Text(model.typecode)
                                    .font(.caption2)
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                            if bbVehicle.abrpCarModel == model.typecode {
                                Image(systemName: "checkmark")
                                    .foregroundColor(.accentColor)
                            }
                        }
                    }
                }
                .searchable(text: $searchText, prompt: "Search models")
            }
        }
        .navigationTitle("ABRP Vehicle Model")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private func load() async {
        isLoading = true
        loadError = nil
        do {
            let client = ABRPClient(
                apiKey: AppSettings.shared.abrpPlanApiKey,
                logSink: HTTPLogSinkManager.shared.createLogSink())
            models = try await client.fetchCarModels()
        } catch {
            loadError = error.localizedDescription
        }
        isLoading = false
    }
}
