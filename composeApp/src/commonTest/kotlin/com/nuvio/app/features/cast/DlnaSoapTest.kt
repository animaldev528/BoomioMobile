package com.nuvio.app.features.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DlnaSoapTest {

    private val description = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <friendlyName>Living Room</friendlyName>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
                <controlURL>/ctl/RenderingControl</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                <controlURL>/ctl/AVTransport</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun controlUrlComesFromTheAvTransportBlockNotTheFirstControlUrl() {
        // RenderingControl is listed first; casting to it "succeeds" and plays nothing.
        assertEquals("/ctl/AVTransport", DlnaSoap.controlUrl(description))
    }

    @Test
    fun missingAvTransportIsNotACastTarget() {
        val noTransport = description.replace("AVTransport:", "MediaRenderer:")
        assertNull(DlnaSoap.controlUrl(noTransport))
        assertFalse(DlnaSoap.hasAvTransport(noTransport))
        assertTrue(DlnaSoap.hasAvTransport(description))
    }

    @Test
    fun relativeControlUrlsResolveAgainstTheDescriptionDirectory() {
        assertEquals(
            "http://10.0.0.5:9197/ctl/AVTransport",
            DlnaSoap.resolve("http://10.0.0.5:9197/dmr", "/ctl/AVTransport"),
        )
        // RFC 3986 resolution: a relative reference replaces the description's
        // last path segment ("/dmr"), it does not nest under it.
        assertEquals(
            "http://10.0.0.5:9197/ctl/AVTransport",
            DlnaSoap.resolve("http://10.0.0.5:9197/dmr", "ctl/AVTransport"),
        )
        assertEquals(
            "http://10.0.0.5:9197/ctl/AVTransport",
            DlnaSoap.resolve("http://10.0.0.5:9197", "ctl/AVTransport"),
        )
        assertEquals(
            "https://elsewhere.example/ctl",
            DlnaSoap.resolve("http://10.0.0.5:9197/dmr", "https://elsewhere.example/ctl"),
        )
    }

    @Test
    fun setAvTransportUriCarriesTheUrlAndEscapedMetadata() {
        val envelope = DlnaSoap.setAvTransportUriEnvelope(
            uri = "https://media.example/a.mkv?x=1&y=2",
            metadataXml = DlnaSoap.didlLite("Fast & Furious", "https://media.example/a.mkv"),
        )
        assertTrue(envelope.contains("<InstanceID>0</InstanceID>"))
        assertTrue(envelope.contains("a.mkv?x=1&amp;y=2"))
        // Metadata is XML inside XML: it must be escaped on the way in.
        assertTrue(envelope.contains("&lt;DIDL-Lite"))
        assertFalse(envelope.contains("<DIDL-Lite"))
        // Double-escaped on purpose: the metadata's own `&amp;` becomes `&amp;amp;`
        // once the whole document travels as a string argument of the SOAP body.
        assertTrue(envelope.contains("Fast &amp;amp; Furious"))
        assertTrue(envelope.contains("urn:schemas-upnp-org:service:AVTransport:1"))
    }

    @Test
    fun didlLiteEscapesTheTitleAndNamesTheClass() {
        val didl = DlnaSoap.didlLite("Tom & Jerry <1965>", "https://media.example/b.mp4")
        assertTrue(didl.startsWith("<DIDL-Lite"))
        assertTrue(didl.contains("<dc:title>Tom &amp; Jerry &lt;1965&gt;</dc:title>"))
        assertTrue(didl.contains("object.item.videoItem"))
        assertTrue(didl.contains("https://media.example/b.mp4"))
    }

    @Test
    fun playEnvelopeNamesThePlayAction() {
        val envelope = DlnaSoap.playEnvelope()
        assertTrue(envelope.contains("<u:Play "))
        assertTrue(envelope.contains("<Speed>1</Speed>"))
    }

    @Test
    fun escapeLeavesOrdinaryTextAlone() {
        assertEquals("The Batman (2022)", DlnaSoap.escape("The Batman (2022)"))
        assertEquals("&quot;quoted&quot;", DlnaSoap.escape("\"quoted\""))
    }
}
