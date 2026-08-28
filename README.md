# Kape Platform VPN orchestrator

Allow Android apps to coordinate VPN connections between multiple endpoints and VPN protocols

At its center is `KapeSessionController`, a protocol-agnostic connect → run → retry loop.
By providing a list of configuration (the information required to make a VPN connection), the session controller
will attempt to connect to each configuration, using the corresponding VPN protocol, in order until a configuration successfully connects.

This SDK provides:
- Connection and reconnection logic
- Wireguard and OpenVPN support
- Connection status
- Pausing and resuming the VPN
- Split-tunneling
- Traffic statistics
- Analytics reporting


---

## Installation

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.pia-foss:platform-sdk:<version>") // exact coordinate TBD once published — see jitpack.yml
}
```

---

## Android Quick Start

`KapeSessionController` runs inside a `VpnService`. The pieces you need to build one:

| Piece | What it is |
|---|---|
| `KapeSystemTunnel` | Wraps your `VpnService`; establishes/tears down the OS tunnel interface. |
| `ConnectionController<Config>` | One per protocol — manages a single tunnel's connect/run/stop lifecycle. `KapeVPN-Wireguard` and `KapeVPN-OpenVPN` each provide one. |
| `VpnConfigurationGenerator` | Your code. Supplies the stream of `VpnConfiguration`s (i.e. your server endpoints) to try, in order. |
| `KapeSessionController` | Ties the above together and runs the session. |

### 1. Implement a `VpnService`

```kotlin
import android.net.VpnService
import com.kape.platformsdk.vpn.service.KapeSessionController
import com.kape.platformsdk.vpn.service.KapeSystemTunnel
import com.kape.platformsdk.vpn.service.interfaces.VpnConfiguration
import com.kape.platformsdk.vpn.service.interfaces.VpnConfigurationGenerator
import com.kape.platformsdk.vpn.service.models.toIpAddress
import com.kape.platformsdk.vpn.wireguard.KapeWireGuardConnectionController
import com.kape.platformsdk.vpn.wireguard.WireGuardAuthConfiguration
import com.kape.platformsdk.vpn.wireguard.WireGuardAuthenticator
import com.kape.platformsdk.vpn.wireguard.WireGuardEndpointConfiguration
import com.kape.platformsdk.vpn.wireguard.WireGuardObfuscation
import com.kape.platformsdk.vpn.wireguard.WireGuardVpnConfiguration

class MyVpnService : VpnService() {
    private lateinit var sessionController: KapeSessionController

    override fun onCreate() {
        super.onCreate()

        val systemTunnel = KapeSystemTunnel(vpnService = this)

        // One ConnectionController per protocol you want to support.
        val wireGuardController =
            KapeWireGuardConnectionController(
                systemTunnel = systemTunnel,
                authenticator = MyWireGuardAuthenticator(),
            )

        // Your own logic: turn "which server to connect to next" into a VpnConfiguration.
        // This is where custom endpoints come in — nothing here has to come from Kape's
        // discovery API, any host/port/keys your app already knows about works.
        val configurationGenerator =
            VpnConfigurationGenerator {
                listOf(
                    WireGuardVpnConfiguration(
                        endpointConfiguration =
                            WireGuardEndpointConfiguration(
                                ip = "203.0.113.10".toIpAddress(),
                                port = 51820,
                                authIp = "203.0.113.10".toIpAddress(),
                                authPort = 443,
                                certDn = "your-server-cert-dn",
                                obfuscation = WireGuardObfuscation.None,
                            ),
                        host = "203.0.113.10",
                        port = 51820,
                        obfuscation = WireGuardObfuscation.None,
                    ),
                )
            }

        sessionController =
            KapeSessionController(
                configurationGenerator = configurationGenerator,
                connectionControllers = listOf(wireGuardController),
                systemTunnel = systemTunnel,
            )
    }

    fun connect() {
        // KapeSessionController.start() launches the run loop and returns immediately;
        // observe sessionController.state.connectionStatus (a StateFlow) for progress.
        CoroutineScope(Dispatchers.IO).launch { sessionController.start() }
    }

    fun disconnect() {
        CoroutineScope(Dispatchers.IO).launch { sessionController.stop() }
    }
}
```

`WireGuardVpnConfiguration` above is built with empty auth fields (`serverPublicKeyBase64`,
`clientPrivateKeyBase64`, …) — `KapeWireGuardConnectionController.connect()` calls your
`WireGuardAuthenticator.authenticate(endpointConfiguration)` to fill those in, so that's the hook
point for your own key-exchange, independent of where the endpoint itself came from:

```kotlin
class MyWireGuardAuthenticator : WireGuardAuthenticator {
    override suspend fun authenticate(
        endpointConfiguration: WireGuardEndpointConfiguration,
    ): WireGuardAuthConfiguration {
        // Provide your Wireguard connection keys here
        return WireGuardAuthConfiguration(
            psk = "…",
            serverPublicKey = "…",
            clientPrivateKey = "…",
            internalIp = "10.0.0.2/32",
        )
    }
}
```

### 2. Support multiple protocols at once

Pass every protocol you want available in one `connectionControllers` list; have your generator
emit whichever `VpnConfiguration` subtype is appropriate for the next server. `KapeSessionController`
dispatches each configuration to the controller registered for its exact type, in the order the
generator emits them — retrying the next one on any connect failure:

```kotlin
KapeSessionController(
    configurationGenerator = configurationGenerator, // can emit both config types
    connectionControllers =
        listOf(
            KapeWireGuardConnectionController(systemTunnel, wireGuardAuthenticator),
            OpenVpnConnectionController(applicationContext, systemTunnel, coroutineScope),
        ),
    systemTunnel = systemTunnel,
)
```

### 3. Observe state and control the session

```kotlin
sessionController.state.connectionStatus.collect { status -> /* Connecting, Connected, … */ }
sessionController.state.lastTunnelError.collect { error -> /* actionable failures */ }

sessionController.forceReconnect() // drop the current connection, advance to the next configuration
sessionController.pause(30.seconds) // bypass the tunnel temporarily, auto-resumes
sessionController.stop()
```

---

## Adding a New VPN Protocol

A protocol is two things: a `VpnConfiguration` describing what it takes to connect, and a
`ConnectionController<YourConfig>` that knows how to actually run it. Neither `KapeSessionController`
nor the run loop needs to change.

### 1. Define your configuration

```kotlin
data class MyProtocolConfiguration(
    val host: String,
    val port: Int,
    // ...whatever your protocol needs
) : VpnConfiguration {
    override val vpnProtocolName: String = "my-protocol"
}
```

### 2. Implement `ConnectionController`

```kotlin
class MyProtocolConnectionController(
    private val systemTunnel: KapeSystemTunnel,
) : ConnectionController<MyProtocolConfiguration> {
    override val configurationClass: KClass<MyProtocolConfiguration> = MyProtocolConfiguration::class
    override var attemptReporter: ConnectionAttemptReporting? = null

    override suspend fun connect(configuration: MyProtocolConfiguration): Boolean {
        val attemptId = attemptReporter?.reportAttemptBegin(configuration)
        // 1. Open your protocol's connection to configuration.host:configuration.port.
        // 2. Bring up the OS tunnel interface via systemTunnel.newBuilder()
        //    .setSession("MyProtocol").addAddress(...).addRoute(...).establish() — mirrors
        //    android.net.VpnService.Builder's fluent surface.
        // 3. Return true once the handshake/connection is actually up; false on any failure.
        //    Report the outcome via attemptReporter?.reportAttemptEnd(...).
    }

    override suspend fun runVPN(): Throwable? {
        // At that point the connection from `connect()` has been successful, the session controller is waiting for the VPN connection to end.
        // `runVPN()` must actually suspend for the tunnel's lifetime — it's what keeps the session "connected"
  from the run loop's point of view.
        // Return null on a clean stop/cancellation,
        // or the failure once the tunnel drops post-connection — KapeSessionController
        // uses a non-null return as the cue to advance to the next configuration.
    }

    override suspend fun stop() {
        // Tear down your protocol's connection and unblock any in-flight runVPN().
    }

    override fun getGateway(): IpAddress? = null // the VPN server's private IP, if known

    // Optional — omit if your protocol doesn't report traffic stats.
    override fun getTrafficStats(): KapeVpnTrafficStats? = null
}
```

### 3. Register it

```kotlin
KapeSessionController(
    configurationGenerator = configurationGenerator, // now free to emit MyProtocolConfiguration too
    connectionControllers = listOf(myProtocolController, /* ...other protocols */),
    systemTunnel = systemTunnel,
)
```

That's the whole integration surface — `KapeSessionController` looks up the right controller by the
emitted configuration's `KClass` at runtime, so existing protocols are untouched.

---

## Package Structure

```
KapeVPN-VpnService/   # com.kape.platformsdk.vpn.service — KapeSessionController, KapeSystemTunnel,
                       # ConnectionController/VpnConfiguration/VpnConfigurationGenerator interfaces
KapeVPN-Wireguard/    # com.kape.platformsdk.vpn.wireguard — KapeWireGuardConnectionController
KapeVPN-OpenVPN/      # com.kape.platformsdk.vpn.openvpn — OpenVpnConnectionController
KapeVPN-PIA/          # fuses the three modules above (plus the AmneziaWG native library) into
                       # the single published AAR: kape-platform-sdk-vpn-pia
```

---

## License

The published artifact is distributed under the [MIT License](https://opensource.org/licenses/MIT).
