package com.papi.nova.nvstream.http

/** What came of asking a host to forget this device. */
enum class HostForgetResult {
    /** The host answered over HTTPS and no longer lists this device. */
    FORGOTTEN,

    /** The host answered, but it has no way to be asked. Sunshine answers 404 here. */
    UNSUPPORTED,

    /** The host said no, or could not save the change, so it still lists this device. */
    REFUSED,

    /** The host could not be reached, so it still lists this device. */
    UNREACHABLE,
}
