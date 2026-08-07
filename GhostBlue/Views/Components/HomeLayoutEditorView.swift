import SwiftUI

struct HomeLayoutEditorView: View {
    @Binding var order: [HomeSection]
    @Environment(\.dismiss) private var dismiss
    @State private var appSettings = AppSettings.shared

    var body: some View {
        NavigationStack {
            List {
                Section("Dashboard Preset") {
                    Picker(
                        "Preset",
                        selection: Binding(
                            get: { appSettings.homeDashboardPreset },
                            set: { appSettings.applyDashboardPreset($0) }
                        )
                    ) {
                        ForEach(HomeDashboardPreset.allCases) { preset in
                            Text(preset.title).tag(preset)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                Section {
                    ForEach(order) { section in
                        HStack {
                            Label(section.title, systemImage: section.systemImage)
                                .foregroundStyle(
                                    appSettings.homeHiddenSections.contains(section)
                                        ? .secondary
                                        : .primary
                                )

                            Spacer()

                            Menu {
                                Picker(
                                    "Card Size",
                                    selection: Binding(
                                        get: { appSettings.size(for: section) },
                                        set: { appSettings.setSize($0, for: section) }
                                    )
                                ) {
                                    ForEach(HomeCardSize.allCases) { size in
                                        Text(size.title).tag(size)
                                    }
                                }

                                Button {
                                    toggleVisibility(section)
                                } label: {
                                    Label(
                                        appSettings.homeHiddenSections.contains(section)
                                            ? "Show Card"
                                            : "Hide Card",
                                        systemImage: appSettings.homeHiddenSections.contains(section)
                                            ? "eye"
                                            : "eye.slash"
                                    )
                                }
                            } label: {
                                Text(appSettings.size(for: section).title)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .onMove { source, destination in
                        order.move(fromOffsets: source, toOffset: destination)
                    }
                } footer: {
                    Text("Drag sections into the order you want them to appear on Home. Unavailable sections stay hidden automatically.")
                }

                Section {
                    Button("Reset Default Order", systemImage: "arrow.counterclockwise") {
                        withAnimation {
                            appSettings.applyDashboardPreset(.detailed)
                        }
                    }
                }
            }
            .environment(\.editMode, .constant(.active))
            .navigationTitle("Customize Home")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private func toggleVisibility(_ section: HomeSection) {
        if appSettings.homeHiddenSections.contains(section) {
            appSettings.homeHiddenSections.remove(section)
        } else {
            appSettings.homeHiddenSections.insert(section)
        }
    }
}
