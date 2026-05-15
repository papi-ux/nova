package com.papi.nova.nvstream.http

import java.security.cert.X509Certificate
import java.util.Objects

class ComputerDetails {
    enum class State {
        ONLINE,
        OFFLINE,
        UNKNOWN,
    }

    enum class LibraryState {
        UNKNOWN,
        AVAILABLE,
        UNAVAILABLE,
    }

    class AddressTuple(address: String?, port: Int) {
        @JvmField
        var address: String

        @JvmField
        var port: Int

        init {
            if (address == null) {
                throw IllegalArgumentException("Address cannot be null")
            }
            if (port <= 0) {
                throw IllegalArgumentException("Invalid port")
            }

            // If this was an escaped IPv6 address, remove the brackets
            this.address = if (address.startsWith("[") && address.endsWith("]")) {
                address.substring(1, address.length - 1)
            } else {
                address
            }
            this.port = port
        }

        override fun hashCode(): Int = Objects.hash(address, port)

        override fun equals(other: Any?): Boolean {
            if (other !is AddressTuple) {
                return false
            }

            return address == other.address && port == other.port
        }

        override fun toString(): String {
            return if (address.contains(":")) {
                // IPv6
                "[$address]:$port"
            } else {
                // IPv4 and hostnames
                "$address:$port"
            }
        }
    }

    // Persistent attributes
    @JvmField var uuid: String = ""
    @JvmField var name: String = ""
    @JvmField var localAddress: AddressTuple? = null
    @JvmField var remoteAddress: AddressTuple? = null
    @JvmField var manualAddress: AddressTuple? = null
    @JvmField var ipv6Address: AddressTuple? = null
    @JvmField var macAddress: String? = null
    @JvmField var serverCert: X509Certificate? = null

    // Transient attributes
    @JvmField var state: State = State.UNKNOWN
    @JvmField var permission: Int = -1
    @JvmField var activeAddress: AddressTuple? = null
    @JvmField var httpsPort: Int = 0
    @JvmField var externalPort: Int = 0
    @JvmField var pairState: PairingManager.PairState? = null
    @JvmField var runningGameId: Int = 0
    @JvmField var runningGameUUID: String? = null
    @JvmField var currentGameOwnedByClient: Boolean? = null
    @JvmField var currentGameOwnerName: String? = null
    @JvmField var currentGameViewerCount: Int = 0
    @JvmField var rawAppList: String? = null
    @JvmField var nvidiaServer: Boolean = false
    @JvmField var serverMaxLaunchRefreshRate: Int = 0
    @JvmField var libraryState: LibraryState = LibraryState.UNKNOWN

    // VDisplay info
    @JvmField var vDisplaySupported: Boolean = false
    @JvmField var vDisplayDriverReady: Boolean = false

    // Server commands
    @JvmField var serverCommands: List<String>? = null

    constructor()

    constructor(details: ComputerDetails) {
        // Copy details from the other computer
        update(details)
    }

    fun guessExternalPort(): Int {
        return if (externalPort != 0) {
            externalPort
        } else if (remoteAddress != null) {
            remoteAddress!!.port
        } else if (activeAddress != null) {
            activeAddress!!.port
        } else if (ipv6Address != null) {
            ipv6Address!!.port
        } else if (localAddress != null) {
            localAddress!!.port
        } else {
            NvHTTP.DEFAULT_HTTP_PORT
        }
    }

    fun update(details: ComputerDetails) {
        state = details.state
        name = details.name
        uuid = details.uuid
        permission = details.permission
        if (details.activeAddress != null) {
            activeAddress = details.activeAddress
        }
        // We can get IPv4 loopback addresses with GS IPv6 Forwarder
        if (details.localAddress != null && !details.localAddress!!.address.startsWith("127.")) {
            localAddress = details.localAddress
        }
        if (details.remoteAddress != null) {
            remoteAddress = details.remoteAddress
        } else if (remoteAddress != null && details.externalPort != 0) {
            // If we have a remote address already (perhaps via STUN) but our updated details
            // don't have a new one (because GFE doesn't send one), propagate the external
            // port to the current remote address. We may have tried to guess it previously.
            remoteAddress!!.port = details.externalPort
        }
        if (details.manualAddress != null) {
            manualAddress = details.manualAddress
        }
        if (details.ipv6Address != null) {
            ipv6Address = details.ipv6Address
        }
        if (details.macAddress != null && details.macAddress != "00:00:00:00:00:00") {
            macAddress = details.macAddress
        }
        if (details.serverCert != null) {
            serverCert = details.serverCert
        }
        externalPort = details.externalPort
        httpsPort = details.httpsPort
        pairState = details.pairState
        runningGameId = details.runningGameId
        runningGameUUID = details.runningGameUUID
        currentGameOwnedByClient = details.currentGameOwnedByClient
        currentGameOwnerName = details.currentGameOwnerName
        currentGameViewerCount = details.currentGameViewerCount
        nvidiaServer = details.nvidiaServer
        rawAppList = details.rawAppList
        serverMaxLaunchRefreshRate = details.serverMaxLaunchRefreshRate
        if (details.libraryState != LibraryState.UNKNOWN ||
            state != State.ONLINE ||
            pairState != PairingManager.PairState.PAIRED
        ) {
            libraryState = details.libraryState
        }

        vDisplayDriverReady = details.vDisplayDriverReady
        vDisplaySupported = details.vDisplaySupported

        serverCommands = details.serverCommands
    }

    override fun toString(): String {
        /*
         * Permissions:
             enum class PERM: uint32_t {
                 _reserved        = 1,

                 _input           = _reserved << 8,   // Input permission group
                 input_controller = _input << 0,      // Allow controller input
                 input_touch      = _input << 1,      // Allow touch input
                 input_pen        = _input << 2,      // Allow pen input
                 input_mouse      = _input << 3,      // Allow mouse input
                 input_kbd        = _input << 4,      // Allow keyboard input
                 _all_inputs      = input_controller | input_touch | input_pen | input_mouse | input_kbd,

                 _operation       = _input << 8,      // Operation permission group
                 clipboard_set    = _operation << 0,  // Allow set clipboard from client
                 clipboard_read   = _operation << 1,  // Allow read clipboard from host
                 file_upload      = _operation << 2,  // Allow upload files to host
                 file_dwnload     = _operation << 3,  // Allow download files from host
                 server_cmd       = _operation << 4,  // Allow execute server cmd
                 _all_opeiations  = clipboard_set | clipboard_read | file_upload | file_dwnload | server_cmd,

                 _action          = _operation << 8,  // Action permission group
                 list             = _action << 0,     // Allow list apps
                 view             = _action << 1,     // Allow view streams
                 launch           = _action << 2,     // Allow launch apps
                 _allow_view      = view | launch,    // Launch contains view permission
                 _all_actions     = list | view | launch,

                 _default         = view | list,      // Default permissions for new clients
                 _no              = 0,                // No permissions are granted
                 _all             = _all_inputs | _all_opeiations | _all_actions, // All current permissions
             };
         */
        val permissionsStr =
            if (permission < 0) {
                "N/A\n"
            } else {
                "0x" + Integer.toHexString(permission) + "\n" +
                    " - Controller Input: " + ((permission and 0x00000100) != 0) + "\n" +
                    " - Touch Input: " + ((permission and 0x00000200) != 0) + "\n" +
                    " - Pen Input: " + ((permission and 0x00000400) != 0) + "\n" +
                    " - Mouse Input: " + ((permission and 0x00000800) != 0) + "\n" +
                    " - Keyboard Input: " + ((permission and 0x00001000) != 0) + "\n" +
                    "\n" +
                    " - Server Command: " + ((permission and 0x00100000) != 0) + "\n" +
                    "\n" +
                    " - List Apps: " + ((permission and 0x01000000) != 0) + "\n" +
                    " - View Streams: " + ((permission and (0x02000000 or 0x01000000)) != 0) + "\n" +
                    " - Launch Apps: " + ((permission and (0x04000000 or 0x02000000 or 0x01000000)) != 0) + "\n"
            }

        return "Name: " + name + "\n" +
            "State: " + state + "\n" +
            "Active Address: " + activeAddress + "\n" +
            "UUID: " + uuid + "\n" +
            "\nPermissions: " + permissionsStr + "\n" +
            "Local Address: " + localAddress + "\n" +
            "Remote Address: " + remoteAddress + "\n" +
            "IPv6 Address: " + ipv6Address + "\n" +
            "Manual Address: " + manualAddress + "\n" +
            "MAC Address: " + macAddress + "\n" +
            "Pair State: " + pairState + "\n" +
            "Running Game ID: " + runningGameId + "\n" +
            "Running Game UUID: " + runningGameUUID + "\n" +
            "Current Game Owned By Client: " + currentGameOwnedByClient + "\n" +
            "Current Game Owner: " + currentGameOwnerName + "\n" +
            "Current Game Viewer Count: " + currentGameViewerCount + "\n" +
            "Server Max Launch Refresh Rate: " + serverMaxLaunchRefreshRate + "\n" +
            "HTTPS Port: " + httpsPort + "\n"
    }
}
