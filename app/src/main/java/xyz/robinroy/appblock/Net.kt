package xyz.robinroy.appblock

import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** The app's two connections (the rules download and feedback) are opened here. */
object Net {

    /**
     * A connection to `url`: HTTPS, or nothing. Anything else isn't an
     * HttpsURLConnection, and the cast throws before a byte is sent.
     * (Android never follows a redirect from HTTPS to HTTP either.) The one
     * exception is Storage.plainHttpAllowed: a developer's own machine,
     * outside release builds.
     */
    fun open(url: URL): HttpURLConnection =
        if (Storage.plainHttpAllowed(url.protocol, url.host, BuildConfig.BUILD_TYPE))
            url.openConnection() as HttpURLConnection
        else url.openConnection() as HttpsURLConnection
}
