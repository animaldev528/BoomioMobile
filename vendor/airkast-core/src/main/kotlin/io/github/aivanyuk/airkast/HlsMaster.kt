package io.github.aivanyuk.airkast

/** Reads an HLS master playlist the way a receiver numbers its tracks. */
public object HlsMaster {
    private const val MEDIA_TAG = "#EXT-X-MEDIA:"

    /**
     * The audio and subtitle renditions of [master], a master playlist's text, with the ids a
     * receiver gives them. Each `EXT-X-MEDIA` tag counts in the order it appears: `TYPE=AUDIO` as
     * [TrackKind.Audio], `SUBTITLES` and `CLOSED-CAPTIONS` as [TrackKind.Subtitles], with audio and
     * subtitles in one count. Other types are skipped. A rendition repeated in another group, with
     * the same type, language and name, counts once.
     *
     * These are the ids the LG CX reported on Apple's bipbop stream and a kino.pub hls4 master, and
     * the LG CX and a Mac on Apple's `bipbop_adv_example_hevc`, which repeats its audio rendition
     * in three groups (docs/compatibility.md). A playlist without `EXT-X-MEDIA` tags, such as a
     * media playlist, has none.
     */
    public fun renditions(master: String): List<Rendition> {
        val seen = HashSet<Triple<String, String?, String?>>()
        val renditions = ArrayList<Rendition>()
        for (line in master.lineSequence()) {
            val tag = line.trim()
            if (!tag.startsWith(MEDIA_TAG)) continue
            val attributes = attributes(tag.substring(MEDIA_TAG.length))
            val type = attributes["TYPE"] ?: continue
            val kind =
                when (type) {
                    "AUDIO" -> TrackKind.Audio
                    "SUBTITLES", "CLOSED-CAPTIONS" -> TrackKind.Subtitles
                    else -> continue
                }
            val name = attributes["NAME"]
            val language = attributes["LANGUAGE"]
            if (!seen.add(Triple(type, language, name))) continue
            renditions +=
                Rendition(
                    kind = kind,
                    id = renditions.size.toLong(),
                    name = name,
                    language = language,
                    forced = attributes["FORCED"] == "YES",
                    characteristics =
                        attributes["CHARACTERISTICS"]
                            ?.split(',')
                            ?.map(String::trim)
                            ?.filter(String::isNotEmpty)
                            .orEmpty(),
                    group = attributes["GROUP-ID"],
                )
        }
        return renditions
    }

    /** An attribute list: `NAME=value` pairs split by commas, where a quoted value may hold commas. */
    private fun attributes(list: String): Map<String, String> {
        val attributes = HashMap<String, String>()
        var i = 0
        while (i < list.length) {
            val equals = list.indexOf('=', i)
            if (equals < 0) break
            val name = list.substring(i, equals).trim()
            val end: Int
            val value: String
            if (equals + 1 < list.length && list[equals + 1] == '"') {
                val close = list.indexOf('"', equals + 2).let { if (it < 0) list.length else it }
                value = list.substring(equals + 2, close)
                end = list.indexOf(',', close).let { if (it < 0) list.length else it }
            } else {
                end = list.indexOf(',', equals + 1).let { if (it < 0) list.length else it }
                value = list.substring(equals + 1, end).trim()
            }
            if (name !in attributes) attributes[name] = value
            i = end + 1
        }
        return attributes
    }
}
