import Foundation
let doubles: [Double] = [1751724753.0, 1751724753.123, 1751724753.5, 12345.0, 12345.6, 0.0001, 0.00001, 1e16, 1.5e16, 123456789012345680.0, 0.1, 100.0, -2.5, 3.0e-7, 9999999999999998.0]
let durations: [Int] = [0, 29, 30, 59, 89, 90, 150, 2700, 3600, 3629, 3631, 3900, 9900, 86400, 90061]
let whole: [Double] = [2.5, 3.5, 182.4, 182.5, 0.5, 1234567.5, 299.99, 72.0]
var out: [String: Any] = [:]
out["description"] = doubles.map { ["value": $0, "text": "\($0)"] }
out["duration"] = durations.map { ["seconds": $0, "text": Duration.seconds($0).formatted(.units(allowed: [.hours, .minutes], width: .abbreviated))] }
let f = NumberFormatter(); f.maximumFractionDigits = 0
out["whole"] = whole.map { ["value": $0, "text": f.string(from: NSNumber(value: $0)) ?? ""] }
let data = try! JSONSerialization.data(withJSONObject: out, options: [.prettyPrinted, .sortedKeys])
print(String(data: data, encoding: .utf8)!)
