//
//  HomeVehicleImageView.swift
//  GhostBlue
//
//  Reuses the widget's app-group car-image cache so the main app and widget
//  show the same vehicle render. If the widget has not cached one yet, the
//  app downloads the same white fallback used by VehicleTimelineProvider.
//

import BetterBlueKit
import SwiftUI
import UIKit

struct HomeVehicleImageView: View {
    let modelName: String
    var colorName: String = VehicleImageColor.white.rawValue
    @State private var image: UIImage?
    @State private var isLoading = true

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .transition(.opacity.combined(with: .scale(scale: 0.96)))
                    .accessibilityLabel("\(modelName) vehicle")
            } else {
                Image(systemName: "car.side.fill")
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .foregroundStyle(
                        LinearGradient(
                            colors: [.white.opacity(0.92), .cyan.opacity(0.55)],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                    .padding(.horizontal, 42)
                    .accessibilityLabel("\(modelName) vehicle")
            }

            if isLoading, image == nil {
                ProgressView()
                    .controlSize(.small)
                    .offset(y: 58)
            }
        }
        .animation(.easeOut(duration: 0.3), value: image != nil)
        .task(id: "\(modelName)-\(colorName)") {
            isLoading = true
            image = await HomeVehicleImageRepository.image(
                for: modelName,
                color: colorName
            )
            isLoading = false
        }
    }
}

@MainActor
enum HomeVehicleImageRepository {
    private static let appGroupID = "group.com.saivenkatkrishna.ghostblue.shared"

    /// Must remain aligned with VehicleTimelineProvider so both targets use
    /// the same on-disk filenames and CDN assets.
    private static let modelImageKeys: [(match: String, key: String)] = [
        ("ioniq5n", "ioniq5n"),
        ("ioniq5", "ioniq5"),
        ("ioniq6", "ioniq6"),
        ("ioniq9", "ioniq9"),
        ("ioniq", "ioniq"),
        ("ev6", "ev6"),
        ("ev9", "ev9"),
        ("kona", "kona"),
        ("niro", "niro")
    ]

    static func image(
        for modelName: String,
        color: String = VehicleImageColor.white.rawValue
    ) async -> UIImage? {
        let key = imageKey(for: modelName)

        if let cached = cachedImage(for: key, color: color) {
            return cached
        }

        if let downloaded = await download(for: key, color: color) {
            return downloaded
        }

        guard color != VehicleImageColor.white.rawValue else { return nil }
        if let cachedWhite = cachedImage(for: key, color: VehicleImageColor.white.rawValue) {
            return cachedWhite
        }
        return await download(for: key, color: VehicleImageColor.white.rawValue)
    }

    private static func imageKey(for modelName: String) -> String {
        let stripped = modelName.replacingOccurrences(
            of: #"^\d{4}\s+"#,
            with: "",
            options: .regularExpression
        )
        let normalized = stripped
            .lowercased()
            .replacingOccurrences(of: " ", with: "")
            .replacingOccurrences(of: "-", with: "")
        return modelImageKeys.first { normalized.contains($0.match) }?.key ?? "ioniq5"
    }

    private static func cacheDirectory() -> URL? {
        let fileManager = FileManager.default
        let base = fileManager.containerURL(
            forSecurityApplicationGroupIdentifier: appGroupID
        ) ?? fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first

        guard let directory = base?.appendingPathComponent("CarImages", isDirectory: true) else {
            return nil
        }

        try? fileManager.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        return directory
    }

    private static func cachedImage(for key: String, color: String) -> UIImage? {
        guard let directory = cacheDirectory() else { return nil }
        let url = directory.appendingPathComponent("\(key)_\(color).png")
        return UIImage(contentsOfFile: url.path)
    }

    private static func download(for key: String, color: String) async -> UIImage? {
        guard let directory = cacheDirectory(),
              let url = URL(
                  string: "https://bluelink.andyfase.com/app-assets/car-images//\(key)/\(color).png"
              )
        else {
            return nil
        }

        let destination = directory.appendingPathComponent("\(key)_\(color).png")

        do {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.timeoutIntervalForRequest = 8
            configuration.timeoutIntervalForResource = 10
            let (data, response) = try await URLSession(
                configuration: configuration
            ).data(from: url)

            guard let http = response as? HTTPURLResponse,
                  http.statusCode == 200,
                  let image = UIImage(data: data)
            else {
                return nil
            }

            try data.write(to: destination, options: .atomic)
            return image
        } catch {
            BBLogger.warning(
                .app,
                "Home: Car image fetch failed for \(key)/\(color): \(error.localizedDescription)"
            )
            return nil
        }
    }
}
