package com.saivenkatkrishna.ghostblue.kit.api.hyundaiusa

import com.saivenkatkrishna.ghostblue.kit.model.Vehicle
import com.saivenkatkrishna.ghostblue.kit.model.VehicleCommand
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal fun HyundaiUSAAPIClient.commandURL(command: VehicleCommand, vehicle: Vehicle): String {
    val electric = vehicle.fuelType.hasElectricCapability
    val path = when (command) {
        VehicleCommand.Unlock -> "ac/v2/rcs/rdo/on"
        VehicleCommand.Lock -> "ac/v2/rcs/rdo/off"
        is VehicleCommand.StartClimate -> if (electric) "ac/v2/evc/fatc/start" else "ac/v2/rcs/rsc/start"
        VehicleCommand.StopClimate -> if (electric) "ac/v2/evc/fatc/stop" else "ac/v2/rcs/rsc/stop"
        VehicleCommand.StartCharge -> "ac/v2/evc/charge/start"
        VehicleCommand.StopCharge -> "ac/v2/evc/charge/stop"
        is VehicleCommand.SetTargetSOC -> "ac/v2/evc/charge/targetsoc/set"
    }
    return "$baseURL/$path"
}

internal fun HyundaiUSAAPIClient.commandBody(command: VehicleCommand, vehicle: Vehicle): JsonObject =
    when (command) {
        is VehicleCommand.StartClimate -> {
            val options = command.options
            val seats = options.seatHeaterVentInfo()
            if (vehicle.fuelType.hasElectricCapability) {
                buildJsonObject {
                    put("airCtrl", if (options.climate) 1 else 0)
                    putJsonObject("airTemp") {
                        put("value", options.temperature.value.toInt().toString())
                        put("unit", options.temperature.units.integer())
                    }
                    put("defrost", options.defrost)
                    put("heating1", options.heatValue)
                    if (vehicle.generation >= 3) {
                        put("igniOnDuration", options.duration)
                        putJsonObject("seatHeaterVentInfo") { seats.forEach { (key, value) -> put(key, value) } }
                    }
                }
            } else {
                buildJsonObject {
                    put("Ims", 0)
                    put("airCtrl", if (options.climate) 1 else 0)
                    putJsonObject("airTemp") {
                        put("unit", options.temperature.units.integer())
                        put("value", options.temperature.value.toInt())
                    }
                    put("defrost", options.defrost)
                    put("heating1", options.heatValue)
                    put("igniOnDuration", options.duration)
                    putJsonObject("seatHeaterVentInfo") { seats.forEach { (key, value) -> put(key, value) } }
                    put("username", username)
                    put("vin", vehicle.vin)
                }
            }
        }
        VehicleCommand.StartCharge -> buildJsonObject { put("chargeRatio", 100) }
        is VehicleCommand.SetTargetSOC -> buildJsonObject {
            putJsonArray("targetSOClist") {
                addJsonObject {
                    put("targetSOClevel", command.acLevel)
                    put("plugType", 1)
                }
                addJsonObject {
                    put("targetSOClevel", command.dcLevel)
                    put("plugType", 0)
                }
            }
        }
        else -> JsonObject(emptyMap())
    }
