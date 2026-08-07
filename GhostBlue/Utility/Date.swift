//
//  Date.swift
//  GhostBlue
//
//  Created by Mark Schmidt on 9/8/25.
//

import Foundation

func formatLastUpdated(_ date: Date?) -> String {
    guard let date else {
        return ""
    }
    let calendar = Calendar.current
    let timeFormatter = DateFormatter()
    timeFormatter.timeStyle = .short

    if calendar.isDateInToday(date) {
        return "Today at \(timeFormatter.string(from: date))"
    } else if calendar.isDateInYesterday(date) {
        return "Yesterday at \(timeFormatter.string(from: date))"
    } else {
        let dayFormatter = DateFormatter()
        dayFormatter.dateStyle = .medium
        dayFormatter.timeStyle = .short
        return dayFormatter.string(from: date)
    }
}

/// Estimated moment charging finishes. The vehicle reports the remaining
/// charge duration as of the status timestamp, so the finish time is
/// anchored to that timestamp — not to "now" — otherwise the estimate
/// drifts later every time the view re-renders.
/// Returns nil when the duration is unknown (≤ 60s) or the estimate is
/// already in the past (stale status).
func chargeFinishDate(remainingSeconds: Int64, reportedAt reference: Date?) -> Date? {
    guard remainingSeconds > 60 else { return nil }
    let finish = (reference ?? Date()).addingTimeInterval(TimeInterval(remainingSeconds))
    guard finish > Date() else { return nil }
    return finish
}

/// "3:41 PM" today, "3:41 AM tomorrow" past midnight, "3:41 PM Sat" beyond.
func formatChargeFinishTime(_ date: Date) -> String {
    let timeFormatter = DateFormatter()
    timeFormatter.timeStyle = .short
    let time = timeFormatter.string(from: date)

    let calendar = Calendar.current
    if calendar.isDateInToday(date) {
        return time
    } else if calendar.isDateInTomorrow(date) {
        return "\(time) tomorrow"
    } else {
        let dayFormatter = DateFormatter()
        dayFormatter.dateFormat = "EEE"
        return "\(time) \(dayFormatter.string(from: date))"
    }
}

func compactLastUpdated(_ date: Date?) -> String {
    guard let date else {
        return ""
    }

    let calendar = Calendar.current
    if calendar.isDateInToday(date) {
        let timeFormatter = DateFormatter()
        timeFormatter.timeStyle = .short
        return timeFormatter.string(from: date)
    } else {
        let dayFormatter = DateFormatter()
        dayFormatter.dateStyle = .medium
        dayFormatter.timeStyle = .none
        return dayFormatter.string(from: date)
    }
}
