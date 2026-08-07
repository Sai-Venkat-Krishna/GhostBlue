//
//  GhostBlueWidgetBundle.swift
//  GhostBlueWidget
//
//  Created by Mark Schmidt on 8/29/25.
//

import AppIntents
import SwiftUI
import WidgetKit

@main
struct GhostBlueWidgetBundle: WidgetBundle {
    // Split into groups: @WidgetBundleBuilder allows at most 10
    // entries per body, and we have 11 widgets in total.
    var body: some Widget {
        homeAndLockScreenWidgets
        controlCenterWidgets
    }

    @WidgetBundleBuilder
    private var homeAndLockScreenWidgets: some Widget {
        GhostBlueWidget()
        GhostBlueGaugeWidget()
        GhostBlueHeroWidget()
        GhostBlueWovenWidget()
        GhostBlueLockScreenWidget()
        VehicleActivityWidget()
    }

    @WidgetBundleBuilder
    private var controlCenterWidgets: some Widget {
        VehicleLockControlWidget()
        VehicleUnlockControlWidget()
        ClimateStartControlWidget()
        ClimateStopControlWidget()
        StartChargeControlWidget()
        StopChargeControlWidget()
    }
}
