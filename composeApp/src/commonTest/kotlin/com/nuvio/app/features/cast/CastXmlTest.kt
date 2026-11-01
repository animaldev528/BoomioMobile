package com.nuvio.app.features.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CastXmlTest {

    @Test
    fun `roku device-info fields are lifted out`() {
        // Trimmed from a real `GET :8060/query/device-info`.
        val xml = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <device-info>
              <udn>3d3a1c9e-0000-1000-8000-aabbccddeeff</udn>
              <is-tv>true</is-tv>
              <is-stick>false</is-stick>
              <model-name>TCL Roku TV</model-name>
              <friendly-device-name>Living Room TV</friendly-device-name>
              <ui-resolution>4k</ui-resolution>
            </device-info>
        """.trimIndent()

        val fields = parseLeafFields(xml)

        assertEquals("Living Room TV", fields["friendly-device-name"])
        assertEquals("true", fields["is-tv"])
        assertEquals("4k", fields["ui-resolution"])
        // The declaration is not a field.
        assertNull(fields["?xml"])
    }

    @Test
    fun `dlna description fields are lifted and lowercased`() {
        val xml = """
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <specVersion><major>1</major><minor>0</minor></specVersion>
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>Bedroom TV</friendlyName>
                <manufacturer>LG Electronics</manufacturer>
                <modelName>LG webOS TV</modelName>
                <modelNumber>OLED55C1PUB</modelNumber>
              </device>
            </root>
        """.trimIndent()

        val fields = parseLeafFields(xml)

        assertEquals("Bedroom TV", fields["friendlyname"])
        assertEquals("LG webOS TV", fields["modelname"])
        assertEquals("OLED55C1PUB", fields["modelnumber"])
        // Container elements with children are not fields…
        assertNull(fields["device"])
        assertNull(fields["root"])
        // …but their leaf children are, nested at any depth.
        assertEquals("1", fields["major"])
    }

    @Test
    fun `the five predefined entities decode`() {
        val fields = parseLeafFields(
            "<t>Tom &amp; Jerry &lt;HD&gt; &quot;x&quot; &#39;y&#39;</t>",
        )
        assertEquals("Tom & Jerry <HD> \"x\" &#39;y&#39;", fields["t"])
    }

    @Test
    fun `an unknown entity is left alone and cannot expand`() {
        // The point of not using a real parser: `&lol;` never becomes anything.
        assertEquals("a &lol; b", parseLeafFields("<t>a &lol; b</t>")["t"])
    }

    @Test
    fun `a mismatched close tag does not match`() {
        assertTrue(parseLeafFields("<a>1</b>").isEmpty())
    }

    @Test
    fun `a tag that appears twice keeps its first value`() {
        assertEquals("first", parseLeafFields("<t>first</t><t>second</t>")["t"])
    }

    @Test
    fun `empty values are dropped and values are trimmed`() {
        val fields = parseLeafFields("<a></a><b>  padded  </b>")
        assertNull(fields["a"])
        assertEquals("padded", fields["b"])
    }

    @Test
    fun `empty input is an empty map`() {
        assertTrue(parseLeafFields("").isEmpty())
        assertTrue(parseLeafFields("not xml at all").isEmpty())
    }
}
