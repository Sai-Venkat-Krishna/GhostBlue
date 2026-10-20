//
//  RivianService.swift
//  GhostBlue
//
//  A Rivian Mobile 3.0–style Service tab built from the support features this
//  app actually has — Troubleshooting, Activity Logs, Backup & Restore and
//  Account — on the RV design system (light + dark). Original UI; no Rivian
//  assets.
//

import BetterBlueKit
import SwiftUI

struct RivianService: View {
    let bbVehicle: BBVehicle
    var onClose: () -> Void = {}

    var body: some View {
        NavigationStack {
            List {
                Section {
                    link("Troubleshooting", "Fix connection and command issues",
                         "wrench.and.screwdriver.fill", RV.climate) { TroubleshootingView() }
                    link("Activity Logs", "Every request sent to your vehicle",
                         "list.bullet.rectangle.fill", RV.location) { HTTPLogView() }
                } header: {
                    Text("Support")
                }
                Section {
                    link("Backup & Restore", "Export or restore your app data",
                         "arrow.triangle.2.circlepath", RV.energy) { BackupRestoreView() }
                    if let account = bbVehicle.account {
                        link("Account", account.username,
                             "person.crop.circle.fill", RV.vehicleCenter) { AccountInfoView(account: account) }
                    }
                } header: {
                    Text("Resources")
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(RVBackground())
            .navigationTitle("Service")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done", action: onClose) }
            }
        }
        .tint(RV.accent)
        .rivianSurfaces()
    }

    private func link<Destination: View>(
        _ title: String, _ subtitle: String?, _ icon: String, _ tint: Color,
        @ViewBuilder destination: @escaping () -> Destination
    ) -> some View {
        NavigationLink {
            destination()
                .rivianListBackground()
        } label: {
            RVRow(title: title, subtitle: subtitle, systemImage: icon, tint: tint, showsChevron: false)
                .padding(.vertical, 2)
        }
        .listRowBackground(RV.surface)
    }
}
