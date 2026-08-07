//
//  GooglePolyline.swift
//  GhostBlue
//
//  Decoder for Google's encoded polyline format, used by the ABRP/Iternio
//  plan API for route geometry. MapKit has no built-in decoder.
//

import CoreLocation
import Foundation

enum GooglePolyline {
    /// Decodes a Google encoded polyline string into coordinates.
    /// ABRP uses the standard 1e5 precision.
    static func decode(_ encoded: String, precision: Double = 1e5) -> [CLLocationCoordinate2D] {
        var coordinates: [CLLocationCoordinate2D] = []
        var index = encoded.utf8.startIndex
        let end = encoded.utf8.endIndex
        var latitude = 0, longitude = 0

        func nextValue() -> Int? {
            var result = 0
            var shift = 0
            while index < end {
                let byte = Int(encoded.utf8[index]) - 63
                index = encoded.utf8.index(after: index)
                guard byte >= 0 else { return nil }
                result |= (byte & 0x1F) << shift
                shift += 5
                if byte < 0x20 {
                    // Zigzag decode
                    return (result & 1) != 0 ? ~(result >> 1) : (result >> 1)
                }
            }
            return nil
        }

        while index < end {
            guard let deltaLat = nextValue(), let deltaLon = nextValue() else { break }
            latitude += deltaLat
            longitude += deltaLon
            coordinates.append(CLLocationCoordinate2D(
                latitude: Double(latitude) / precision,
                longitude: Double(longitude) / precision,
            ))
        }
        return coordinates
    }
}
