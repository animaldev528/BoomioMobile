package com.nuvio.app.features.cast

/**
 * The SOAP / DIDL half of casting to a DLNA renderer.
 *
 * Kept as pure string work in commonMain: the transport is the only part that has
 * to be Android, and a casting bug that only reproduces on a television is the
 * most expensive kind to find. Everything here can be asserted on the JVM.
 *
 * A UPnP renderer is driven in two steps, both POSTed to the `controlURL` of its
 * `AVTransport` service:
 *
 *   1. `SetAVTransportURI` — "here is the thing to play"
 *   2. `Play`               — "start it"
 *
 * Step 1 carries the renderer's `CurrentURIMetaData`: a DIDL-Lite document that
 * is itself XML, so it travels XML-escaped inside the envelope. Renderers differ
 * on whether they read it, but the ones that do refuse to play without it, and
 * sending it costs nothing.
 */
internal object DlnaSoap {

    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

    /**
     * The `<service>` block that declares [serviceType], or null.
     *
     * A description lists several services (`AVTransport`, `RenderingControl`,
     * `ConnectionManager`) and each has its own `controlURL`, so the URL has to be
     * read out of the RIGHT block. Taking the first `controlURL` in the document
     * is how a cast ends up "succeeding" against a volume endpoint that ignores it.
     */
    fun serviceBlock(description: String, serviceType: String): String? {
        for (block in blocks(description, "service")) {
            if (childText(block, "serviceType")?.trim() == serviceType) return block
        }
        return null
    }

    /** The `AVTransport` control URL exactly as the renderer wrote it (may be relative). */
    fun controlUrl(description: String): String? =
        serviceBlock(description, AV_TRANSPORT)
            ?.let { childText(it, "controlURL") }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** True when the description offers an `AVTransport` service at all. */
    fun hasAvTransport(description: String): Boolean =
        description.contains(":service:AVTransport:", ignoreCase = true)

    fun setAvTransportUriEnvelope(uri: String, metadataXml: String): String =
        envelope(
            action = "SetAVTransportURI",
            arguments = buildString {
                append("<InstanceID>0</InstanceID>")
                append("<CurrentURI>").append(escape(uri)).append("</CurrentURI>")
                append("<CurrentURIMetaData>").append(escape(metadataXml)).append("</CurrentURIMetaData>")
            },
        )

    fun playEnvelope(): String =
        envelope(
            action = "Play",
            arguments = "<InstanceID>0</InstanceID><Speed>1</Speed>",
        )

    /**
     * What the renderer shows while it loads, and what it uses to decide whether
     * the item is video at all. `protocolInfo` is a guess at the container
     * (`http-get:*:video/mp4:*`): the renderer streams the URL itself, so a wrong
     * MIME here is cosmetic on every device we have seen, while omitting the
     * element gets the item rejected as "unknown type" on some.
     */
    fun didlLite(title: String, uri: String, mimeType: String = "video/mp4"): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"" +
            " xmlns:dc=\"http://purl.org/dc/elements/1.1/\"" +
            " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"1\" parentID=\"0\" restricted=\"1\">" +
            "<dc:title>${escape(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:${escape(mimeType)}:*\">${escape(uri)}</res>" +
            "</item>" +
            "</DIDL-Lite>"

    /**
     * Turns the renderer's `controlURL` into an absolute URL.
     *
     * UPnP allows both an absolute URL and a path relative to the description's
     * own directory (`ctl/AVTransport`), and renderers use both. Resolving against
     * the description URL is what makes the second form work at all.
     */
    fun resolve(descriptionUrl: String, controlUrl: String): String {
        val target = controlUrl.trim()
        if (target.startsWith("http://", ignoreCase = true) ||
            target.startsWith("https://", ignoreCase = true)
        ) {
            return target
        }
        val base = descriptionUrl.trim()
        val schemeEnd = base.indexOf("://")
        if (schemeEnd <= 0) return target
        val hostStart = schemeEnd + 3
        val pathStart = base.indexOf('/', hostStart).let { if (it < 0) base.length else it }
        val origin = base.substring(0, pathStart)
        if (target.startsWith("/")) return origin + target
        if (target.isEmpty()) return base
        val dirEnd = base.lastIndexOf('/').takeIf { it > hostStart } ?: pathStart
        return base.substring(0, dirEnd).trimEnd('/') + "/" + target
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun envelope(action: String, arguments: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"" +
            " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>" +
            "<u:$action xmlns:u=\"$AV_TRANSPORT\">$arguments</u:$action>" +
            "</s:Body>" +
            "</s:Envelope>"

    private fun blocks(xml: String, tag: String): List<String> =
        Regex("<$tag>(.*?)</$tag>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(xml)
            .map { it.groupValues[1] }
            .toList()

    /** First occurrence of `<tag>text</tag>` inside [xml], or null. */
    private fun childText(xml: String, tag: String): String? =
        Regex("<$tag>([^<]*)</$tag>", setOf(RegexOption.IGNORE_CASE))
            .find(xml)
            ?.groupValues
            ?.get(1)

    /**
     * The five predefined entities plus quotes. Percent-encoding is NOT right here:
     * this text lands inside an XML document, and a title with an `&` — "Fast &
     * Furious" — breaks the whole envelope on the renderer's parser if it is not
     * escaped.
     */
    fun escape(value: String): String = buildString(value.length + 16) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }
}
