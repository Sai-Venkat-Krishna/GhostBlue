package com.saivenkatkrishna.ghostblue.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saivenkatkrishna.ghostblue.kit.api.APIClientConfiguration
import com.saivenkatkrishna.ghostblue.kit.api.fake.FakeAPIClient
import com.saivenkatkrishna.ghostblue.kit.api.fake.FakeVehicleSeeds
import com.saivenkatkrishna.ghostblue.kit.api.fake.InMemoryFakeVehicleStore
import com.saivenkatkrishna.ghostblue.kit.api.fake.StoreFakeVehicleProvider
import com.saivenkatkrishna.ghostblue.kit.model.APIError
import com.saivenkatkrishna.ghostblue.kit.model.AuthToken
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.Region
import com.saivenkatkrishna.ghostblue.kit.model.Vehicle
import com.saivenkatkrishna.ghostblue.kit.model.VehicleCommand
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VehicleCardState(val vehicle: Vehicle, val status: VehicleStatus?, val busy: Boolean = false)

data class TestCarUiState(
    val loading: Boolean = true,
    val vehicles: List<VehicleCardState> = emptyList(),
    val message: String? = null,
)

/**
 * Phase-one preview: drives the test car through the Kotlin vehicle API so
 * the port can be tried on a phone before the real screens exist.
 */
class TestCarViewModel : ViewModel() {
    private val configuration = APIClientConfiguration(
        region = Region.USA,
        brand = Brand.FAKE,
        username = "testaccount@betterblue.com",
        password = "betterblue",
        pin = "",
        accountId = UUID.randomUUID(),
    )
    private val client = FakeAPIClient(
        configuration,
        StoreFakeVehicleProvider(InMemoryFakeVehicleStore(FakeVehicleSeeds.defaultRecords(configuration.accountId))),
    )
    private var token: AuthToken? = null

    private val _state = MutableStateFlow(TestCarUiState())
    val state: StateFlow<TestCarUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null) }
            try {
                val token = token()
                val cards = client.fetchVehicles(token).map { vehicle ->
                    VehicleCardState(vehicle, client.fetchVehicleStatus(vehicle, token))
                }
                _state.value = TestCarUiState(loading = false, vehicles = cards)
            } catch (error: APIError) {
                _state.update { it.copy(loading = false, message = error.message) }
            }
        }
    }

    fun send(vehicle: Vehicle, command: VehicleCommand) {
        viewModelScope.launch {
            setBusy(vehicle, true)
            try {
                val token = token()
                client.sendCommand(vehicle, command, token)
                val status = client.fetchVehicleStatus(vehicle, token, cached = false)
                _state.update { state ->
                    state.copy(
                        vehicles = state.vehicles.map {
                            if (it.vehicle.vin == vehicle.vin) VehicleCardState(vehicle, status) else it
                        },
                    )
                }
            } catch (error: APIError) {
                setBusy(vehicle, false)
                _state.update { it.copy(message = error.message) }
            }
        }
    }

    private suspend fun token(): AuthToken =
        token?.takeIf { it.isValid } ?: client.login().also { token = it }

    private fun setBusy(vehicle: Vehicle, busy: Boolean) {
        _state.update { state ->
            state.copy(vehicles = state.vehicles.map { if (it.vehicle.vin == vehicle.vin) it.copy(busy = busy) else it })
        }
    }
}
