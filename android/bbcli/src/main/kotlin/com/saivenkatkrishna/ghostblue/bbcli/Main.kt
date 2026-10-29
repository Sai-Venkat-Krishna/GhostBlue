package com.saivenkatkrishna.ghostblue.bbcli

import com.saivenkatkrishna.ghostblue.kit.api.APIClient
import com.saivenkatkrishna.ghostblue.kit.api.APIClientConfiguration
import com.saivenkatkrishna.ghostblue.kit.api.createBetterBlueKitAPIClient
import com.saivenkatkrishna.ghostblue.kit.api.fake.FakeAPIClient
import com.saivenkatkrishna.ghostblue.kit.api.fake.FakeVehicleSeeds
import com.saivenkatkrishna.ghostblue.kit.api.fake.InMemoryFakeVehicleStore
import com.saivenkatkrishna.ghostblue.kit.api.fake.StoreFakeVehicleProvider
import com.saivenkatkrishna.ghostblue.kit.api.hyundaiusa.HyundaiUSAAPIClient
import com.saivenkatkrishna.ghostblue.kit.api.hyundaiusa.parseEVTripDetailsResponse
import com.saivenkatkrishna.ghostblue.kit.api.hyundaiusa.parseVehicleStatusResponse
import com.saivenkatkrishna.ghostblue.kit.api.hyundaiusa.parseVehiclesResponse
import com.saivenkatkrishna.ghostblue.kit.model.APIError
import com.saivenkatkrishna.ghostblue.kit.model.AuthToken
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.ClimateOptions
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.FuelType
import com.saivenkatkrishna.ghostblue.kit.model.KitJson
import com.saivenkatkrishna.ghostblue.kit.model.Region
import com.saivenkatkrishna.ghostblue.kit.model.Temperature
import com.saivenkatkrishna.ghostblue.kit.model.Vehicle
import com.saivenkatkrishna.ghostblue.kit.model.VehicleCommand
import com.saivenkatkrishna.ghostblue.kit.model.isTestAccount
import java.io.File
import java.util.UUID
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/*
 * Kotlin counterpart of BetterBlueKit's bbcli.
 *
 *   bbcli [-u user] [-p password] [--pin 1234] [-b hyundai] [-r US] [--no-redaction]
 *       Signs in and opens a menu of vehicle commands. Prompts for anything
 *       not given; the password prompt doesn't echo.
 *
 *   bbcli parse -t vehicles|status|trips [-b hyundai] [-r US] [--vin VIN] [--electric|--phev] <json|file>
 *       Runs a saved API response through the parser and prints the result as
 *       JSON, like the Swift tool's parse mode.
 */

private val prettyJson = Json(KitJson) { prettyPrint = true }

fun main(args: Array<String>) {
    val code = runBlocking {
        if (args.firstOrNull() == "parse") runParseMode(args.drop(1)) else runCLI(args.toList())
    }
    exitProcess(code)
}

// MARK: - Interactive mode

private class CLIState {
    var username = ""
    var password = ""
    var pin = ""
    var brand = Brand.HYUNDAI
    var region = Region.USA
    var redact = true
    lateinit var client: APIClient
    lateinit var token: AuthToken
    var vehicles: List<Vehicle> = emptyList()
}

private suspend fun runCLI(args: List<String>): Int {
    val state = CLIState()
    val arguments = args.iterator()
    while (arguments.hasNext()) {
        when (val arg = arguments.next()) {
            "-u", "--username" -> state.username = arguments.nextOrEmpty()
            "-p", "--password" -> state.password = arguments.nextOrEmpty()
            "--pin" -> state.pin = arguments.nextOrEmpty()
            "-b", "--brand" -> {
                val value = arguments.nextOrEmpty()
                state.brand = Brand.fromRawValue(value.lowercase()) ?: return fail("Unknown brand: $value")
            }
            "-r", "--region" -> {
                val value = arguments.nextOrEmpty()
                state.region = Region.fromRawValue(value.uppercase()) ?: return fail("Unknown region: $value")
            }
            "--no-redaction" -> state.redact = false
            "-h", "--help" -> {
                printUsage()
                return 0
            }
            else -> return fail("Unknown option: $arg")
        }
    }

    if (state.username.isEmpty()) state.username = prompt("Username/Email: ")
    if (state.password.isEmpty()) state.password = promptSecret("Password: ")
    if (state.brand == Brand.HYUNDAI && state.pin.isEmpty()) {
        state.pin = promptSecret("PIN: ").filter { it.isDigit() }
    }

    return try {
        login(state)
        runMenu(state)
        0
    } catch (error: APIError) {
        printError("${error.errorType.rawValue}: ${error.message}")
        1
    }
}

private suspend fun login(state: CLIState) {
    val configuration = APIClientConfiguration(
        region = state.region,
        brand = state.brand,
        username = state.username,
        password = state.password,
        pin = state.pin,
        accountId = UUID.randomUUID(),
        redactPII = state.redact,
    )
    state.client = if (state.brand == Brand.FAKE || isTestAccount(state.username, state.password)) {
        println("Using the test car (fake API).")
        val store = InMemoryFakeVehicleStore(FakeVehicleSeeds.defaultRecords(configuration.accountId))
        FakeAPIClient(configuration, StoreFakeVehicleProvider(store))
    } else {
        createBetterBlueKitAPIClient(configuration)
    }
    printHeader("Logging in to ${state.brand.displayName} (${state.region.rawValue})")
    state.token = state.client.login()
    printSuccess("Logged in (token expires ${state.token.expiresAt})")
}

private suspend fun runMenu(state: CLIState) {
    while (true) {
        println(
            """

            Commands:
              1. Fetch Vehicles
              2. Fetch Vehicle Status
              3. Lock Vehicle
              4. Unlock Vehicle
              5. Start Climate
              6. Stop Climate
              7. Start Charge
              8. Stop Charge
              9. Set Charge Limits
             10. Fetch EV Trip Details
              0. Exit
            """.trimIndent(),
        )
        val choice = prompt("Enter command number: ")
        try {
            when (choice) {
                "1" -> fetchVehicles(state)
                "2" -> fetchStatus(state)
                "3" -> send(state, VehicleCommand.Lock, "Lock")
                "4" -> send(state, VehicleCommand.Unlock, "Unlock")
                "5" -> {
                    val temperature = prompt("Temperature (default 72°F): ").toDoubleOrNull() ?: 72.0
                    val options = ClimateOptions(temperature = Temperature(temperature, Temperature.Units.FAHRENHEIT))
                    send(state, VehicleCommand.StartClimate(options), "Start Climate")
                }
                "6" -> send(state, VehicleCommand.StopClimate, "Stop Climate")
                "7" -> send(state, VehicleCommand.StartCharge, "Start Charge")
                "8" -> send(state, VehicleCommand.StopCharge, "Stop Charge")
                "9" -> {
                    val ac = prompt("AC Charge Limit (50-100): ").toIntOrNull() ?: 80
                    val dc = prompt("DC Charge Limit (50-100): ").toIntOrNull() ?: 80
                    send(state, VehicleCommand.SetTargetSOC(ac, dc), "Set Charge Limits")
                }
                "10" -> fetchTrips(state)
                "0", "q", "quit", "exit" -> {
                    println("\nGoodbye!")
                    return
                }
                else -> printError("Invalid command")
            }
        } catch (error: APIError) {
            printError("${error.errorType.rawValue}: ${error.message}")
            error.userInfo?.let { println("User Info: $it") }
        }
    }
}

private suspend fun fetchVehicles(state: CLIState) {
    printHeader("Fetching Vehicles")
    state.vehicles = state.client.fetchVehicles(state.token)
    printSuccess("Found ${state.vehicles.size} vehicle(s)")
    state.vehicles.forEachIndexed { index, vehicle ->
        println("\n[${index + 1}] ${vehicle.model}")
        println("    VIN: ${maskedVIN(vehicle.vin, state.redact)}")
        println("    Fuel Type: ${vehicle.fuelType.rawValue}")
        println("    Generation: ${vehicle.generation}")
    }
}

private suspend fun selectVehicle(state: CLIState): Vehicle? {
    if (state.vehicles.isEmpty()) fetchVehicles(state)
    if (state.vehicles.size == 1) return state.vehicles.first()
    println("\nSelect a vehicle:")
    state.vehicles.forEachIndexed { index, vehicle ->
        println("  ${index + 1}. ${vehicle.model} (${maskedVIN(vehicle.vin, state.redact)})")
    }
    val choice = prompt("Vehicle number: ").toIntOrNull()
    return state.vehicles.getOrNull((choice ?: 0) - 1) ?: run {
        printError("Invalid selection")
        null
    }
}

private suspend fun fetchStatus(state: CLIState) {
    val vehicle = selectVehicle(state) ?: return
    val live = prompt("Ask the car for live status? Slower, wakes the car (y/N): ").lowercase() == "y"
    printHeader("Fetching Status for ${vehicle.model}")
    val status = state.client.fetchVehicleStatus(vehicle, state.token, cached = !live)
    printSuccess("Status fetched successfully")
    println("\n📊 Status Summary:")
    println("  Lock Status: ${status.lockStatus}")
    println("  Climate On: ${status.climateStatus.airControlOn}")
    println("  Climate Temp: ${status.climateStatus.temperature.value}${status.climateStatus.temperature.units.symbol}")
    status.evStatus?.let { ev ->
        println("  🔋 EV Status:")
        println("     Battery: ${ev.evRange.percentage.toInt()}%")
        println("     Range: ${ev.evRange.range.length.toInt()} ${ev.evRange.range.units.abbreviation}")
        println("     Plugged In: ${ev.pluggedIn}")
        println("     Charging: ${ev.charging} (${ev.chargeSpeed} kW)")
        ev.targetSocAC?.let { println("     Target SOC (AC): ${it.toInt()}%") }
        ev.targetSocDC?.let { println("     Target SOC (DC): ${it.toInt()}%") }
    }
    status.gasRange?.let { gas ->
        println("  ⛽ Fuel Status:")
        println("     Fuel: ${gas.percentage.toInt()}%")
        println("     Range: ${gas.range.length.toInt()} ${gas.range.units.abbreviation}")
    }
}

private suspend fun send(state: CLIState, command: VehicleCommand, description: String) {
    val vehicle = selectVehicle(state) ?: return
    printHeader("Sending $description to ${vehicle.model}")
    state.client.sendCommand(vehicle, command, state.token)
    printSuccess("$description command sent successfully!")
}

private suspend fun fetchTrips(state: CLIState) {
    val vehicle = selectVehicle(state) ?: return
    printHeader("Fetching EV Trip Details for ${vehicle.model}")
    val trips = state.client.fetchEVTripDetails(vehicle, state.token)
    if (trips == null) {
        printError("This vehicle or API has no trip details")
        return
    }
    printSuccess("Found ${trips.size} trip(s)")
    trips.take(5).forEachIndexed { index, trip ->
        println("\n[${index + 1}] Trip on ${trip.startDate}")
        println("    Distance: ${trip.distance} mi")
        println("    Duration: ${trip.formattedDuration}")
        println("    Energy Used: ${trip.totalEnergyUsed / 1000.0} kWh")
    }
}

// MARK: - Parse mode

private fun runParseMode(args: List<String>): Int {
    var brand = Brand.HYUNDAI
    var region = Region.USA
    var type: String? = null
    var vin = "TESTVIN0000000000"
    var fuelType = FuelType.GAS
    var input: String? = null
    val arguments = args.iterator()
    while (arguments.hasNext()) {
        when (val arg = arguments.next()) {
            "-b", "--brand" -> {
                val value = arguments.nextOrEmpty()
                brand = Brand.fromRawValue(value.lowercase()) ?: return fail("Unknown brand: $value")
            }
            "-r", "--region" -> {
                val value = arguments.nextOrEmpty()
                region = Region.fromRawValue(value.uppercase()) ?: return fail("Unknown region: $value")
            }
            "-t", "--type" -> type = arguments.nextOrEmpty().lowercase()
            "--vin" -> vin = arguments.nextOrEmpty()
            "--electric" -> fuelType = FuelType.ELECTRIC
            "--phev" -> fuelType = FuelType.PHEV
            "-h", "--help" -> {
                printUsage()
                return 0
            }
            // The last other argument is the JSON (or a path to it).
            else -> input = arg
        }
    }
    val json = input ?: return fail("JSON input is required (JSON string or path to file)")
    val data = unwrapResponseBody(File(json).takeIf { it.exists() }?.readBytes() ?: json.toByteArray())

    return try {
        val client = createBetterBlueKitAPIClient(
            APIClientConfiguration(region, brand, "parse-mode", "", "", UUID.randomUUID()),
        ) as? HyundaiUSAAPIClient ?: return fail("Parse mode supports Hyundai USA")
        printHeader("Parsing $type (${brand.displayName} ${region.rawValue})")
        when (type) {
            "vehicles" -> {
                val vehicles = client.parseVehiclesResponse(data)
                printSuccess("Parsed ${vehicles.size} vehicle(s)")
                println(prettyJson.encodeToString(ListSerializer(Vehicle.serializer()), vehicles))
            }
            "vehiclestatus", "status" -> {
                val vehicle = Vehicle(
                    vin = vin,
                    regId = "parse-mode",
                    model = "parse-mode",
                    accountId = UUID.randomUUID(),
                    fuelType = fuelType,
                    generation = 2,
                    odometer = Distance(0.0, Distance.Units.MILES),
                )
                val status = client.parseVehicleStatusResponse(data, vehicle)
                printSuccess("Parsed vehicle status")
                println(prettyJson.encodeToString(status))
            }
            "trips" -> {
                val trips = client.parseEVTripDetailsResponse(data)
                printSuccess("Parsed ${trips.size} trip(s)")
                println(prettyJson.encodeToString(trips))
            }
            else -> return fail("Parse type is required. Use -t vehicles, -t vehicleStatus or -t trips")
        }
        0
    } catch (error: APIError) {
        printError("${error.errorType.rawValue}: ${error.message}")
        1
    }
}

/** Accepts a `{"responseBody": {...}}` wrapper, as the Swift tool does. */
private fun unwrapResponseBody(data: ByteArray): ByteArray {
    val json = runCatching { Json.parseToJsonElement(data.decodeToString()) as? JsonObject }.getOrNull() ?: return data
    val body = json["responseBody"] ?: return data
    if (json.size > 2) return data
    println("Auto-unwrapped 'responseBody' wrapper")
    return body.toString().toByteArray()
}

// MARK: - Output

private fun prompt(message: String): String {
    print(message)
    System.out.flush()
    return readlnOrNull()?.trim().orEmpty()
}

/** Reads without echo when there's a terminal. */
private fun promptSecret(message: String): String =
    System.console()?.readPassword(message)?.let(::String) ?: prompt(message)

private fun maskedVIN(vin: String, redact: Boolean) =
    if (redact && vin.length > 7) vin.take(3) + "*".repeat(vin.length - 7) + vin.takeLast(4) else vin

private fun printHeader(text: String) = println("\n== $text ==")
private fun printSuccess(text: String) = println("✅ $text")
private fun printError(text: String) = System.err.println("❌ $text")

private fun Iterator<String>.nextOrEmpty(): String = if (hasNext()) next() else ""

private fun fail(message: String): Int {
    printError(message)
    return 1
}

private fun printUsage() {
    println(
        """
        Usage:
          bbcli [-u username] [-p password] [--pin 1234] [-b hyundai|fake] [-r US] [--no-redaction]
          bbcli parse -t vehicles|status|trips [-b hyundai] [-r US] [--vin VIN] [--electric|--phev] <json|file>

        The test account (testaccount@betterblue.com / betterblue) uses the test car.
        """.trimIndent(),
    )
}
