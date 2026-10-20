//
//  RivianControls.swift
//  GhostBlue
//
//  A Rivian Mobile 3.0–style Controls screen: a reorderable list of the
//  vehicle's remote commands on the RV design system (light + dark). Mirrors
//  Rivian's "press, hold and rearrange" personalization — the order is
//  persisted — while reusing the app's functional command components
//  (Lock / Climate / Charging) so each keeps its status-waiting, MFA and error
//  handling. Original UI; no Rivian assets.
//

import BetterBlueKit
import SwiftUI

struct RivianControls: View {
    let bbVehicle: BBVehicle
    var transition: Namespace.ID?
    var onClose: () -> Void = {}

    /// Available remote commands for this vehicle, by stable id. Charging
    /// is offered only to cars that report EV status, as on classic Home.
    private var allControls: [String] {
        bbVehicle.evStatus != nil ? ["lock", "climate", "charging"] : ["lock", "climate"]
    }

    /// Persisted order (comma-joined ids).
    @AppStorage("rivianControlsOrder") private var orderRaw: String = ""
    @State private var order: [String] = []

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(orderedControls, id: \.self) { id in
                        controlRow(id)
                            .padding(.vertical, RV.Space.xs)
                            .listRowBackground(RV.surface)
                            .listRowSeparatorTint(RV.hairline)
                    }
                    .onMove(perform: move)
                } header: {
                    Text("Remote commands")
                } footer: {
                    Text("Tap Edit, then drag to rearrange your remote commands.")
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(RVBackground())
            .navigationTitle("Controls")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { EditButton() }
                ToolbarItem(placement: .topBarTrailing) { Button("Done", action: onClose) }
            }
            .onAppear(perform: loadOrder)
        }
        .tint(RV.accent)
        .rivianSurfaces()
    }

    /// Saved order, with any newly-available commands appended so nothing is lost.
    private var orderedControls: [String] {
        let saved = order.filter { allControls.contains($0) }
        let missing = allControls.filter { !saved.contains($0) }
        return saved + missing
    }

    @ViewBuilder
    private func controlRow(_ id: String) -> some View {
        switch id {
        case "lock": LockButton(bbVehicle: bbVehicle, transition: transition)
        case "climate": ClimateButton(bbVehicle: bbVehicle, transition: transition)
        case "charging": ChargingButton(bbVehicle: bbVehicle, transition: transition)
        default: EmptyView()
        }
    }

    private func loadOrder() {
        let saved = orderRaw.split(separator: ",").map(String.init).filter { allControls.contains($0) }
        order = saved.isEmpty ? allControls : saved
    }

    private func move(from source: IndexSet, to destination: Int) {
        var current = orderedControls
        current.move(fromOffsets: source, toOffset: destination)
        order = current
        orderRaw = current.joined(separator: ",")
    }
}
