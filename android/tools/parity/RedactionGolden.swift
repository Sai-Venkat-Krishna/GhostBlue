import Foundation
let inputs: [String] = [
    #"{"username": "john.doe@example.com", "password": "hunter2", "pin": "1234"}"#,
    #"{"email":"a@b.co","loginId":"x.y@mail.example.org","notificationEmail":"n@n.io"}"#,
    #"{"access_token": "eyJhbGciOi.abc-def_123", "refresh_token":"r\"t\\ok", "otpKey": "k"}"#,
    #"Authorization: Bearer abc.DEF-123_xyz and more"#,
    #"{"vehicleLocation":{"coord":{"lat":32.7157,"lon":-117.1611,"alt":20}},"latitude": -33.8688,"lng":151.2093}"#,
    #"coords [32.715736, -117.161087] and 1.5, 2.5"#,
    #"{"firstName":"Sai","lastName":"K","phone":"555-0100","phoneNumber":"+1 555","number":"42"}"#,
    #"{"accountId":"A1","user_id":"u","deviceid":"d","enrollmentCode":"E","clientuuid":"c"}"#,
    #"{"street":"915 Long Drive","postalCode":"62305","city":"Springfield"}"#,
    #"{"vin":"KM8KRDAF5PU000001","VIN":"5NPEG4JA0MH000003","vin2":"KM8KRDAF5PU000001"}"#,
    #"{"regid":"H00000000000000001","regId":"R","regID":"Q"}"#,
    #"GET https://api.telematics.hyundaiusa.com/ac/v2/enrollment/details/someone@example.com"#,
    #"{"userId":"someone@example.com"}"#,
    #"{"Cookie":"__cf_bm=abc","TransactionId":"t-1","Pauth":"p"}"#,
    #"plain text with nothing sensitive"#,
    #"{"vin":"short"}"#,
    ""
]
var results: [[String: String]] = []
for input in inputs {
    results.append(["input": input, "expected": SensitiveDataRedactor.redact(input) ?? ""])
}
let headers = ["Authorization": "Bearer x", "accessToken": "a", "blueLinkServicePin": "1234", "clientSecret": "s", "Content-Type": "application/json", "client_id": "id", "username": "u@x.com", "X-Auth-Thing": "z", "Set-Cookie": "c"]
let redactedHeaders = SensitiveDataRedactor.redactHeaders(headers)
let output: [String: Any] = ["strings": results, "headers": ["input": headers, "expected": redactedHeaders]]
let data = try! JSONSerialization.data(withJSONObject: output, options: [.prettyPrinted, .sortedKeys])
print(String(data: data, encoding: .utf8)!)
