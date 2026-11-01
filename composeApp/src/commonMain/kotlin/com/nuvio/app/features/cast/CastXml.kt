package com.nuvio.app.features.cast

/**
 * Reads the flat `<tag>text</tag>` fields out of a small discovery document.
 *
 * Two shapes matter and both are flat:
 *   - Roku ECP `GET /query/device-info` — `<device-info><model-name>…</model-name>…`
 *   - a DLNA device description — `<root><device><friendlyName>…</friendlyName>…`
 *
 * ⚠️ Deliberately not a DOM/XML parser. These documents come from an unauthenticated
 * device on the LAN, and a real parser brings entity-expansion and external-entity
 * behaviour with it. Scanning for leaf elements cannot resolve an entity it was not
 * handed, so a hostile renderer can produce garbage fields and nothing else.
 *
 * Keys are lowercased so callers never have to guess a case, and a repeated tag
 * keeps its first value.
 */
internal fun parseLeafFields(xml: String): Map<String, String> {
    if (xml.isEmpty()) return emptyMap()
    val fields = LinkedHashMap<String, String>()
    for (match in LEAF_ELEMENT.findAll(xml)) {
        val tag = match.groupValues[1].lowercase()
        val value = decodeEntities(match.groupValues[2]).trim()
        if (value.isNotEmpty()) fields.putIfAbsent(tag, value)
    }
    return fields
}

// `<name>text</name>` with no nested element in `text`. Backreference \1 keeps the
// close tag honest, so `<a>1</b>` does not match.
private val LEAF_ELEMENT = Regex("<([A-Za-z][A-Za-z0-9_:.\\-]*)>([^<]*)</\\1>")

/**
 * The five predefined entities plus numeric references. Anything else is left
 * alone: unknown named entities never expand, which is the point.
 */
private fun decodeEntities(value: String): String {
    if (value.indexOf('&') < 0) return value
    return value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
