package bnbemu.server

/** Only scripted self-damage declared by that map's actor may bypass ice freeze. */
object LocalMonsterScriptDamage {
    private val limits: Map<Pair<String, String>, Int> by lazy {
        checkNotNull(javaClass.getResourceAsStream("/data/local-monster-script-self-damage.tsv"))
            .bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.associate { line ->
                    val fields = line.split('\t')
                    (fields[0] to fields[1]) to fields[2].toInt()
                }
            }
    }

    fun permits(map: String, actor: LocalMonster, damage: Int): Boolean {
        val key = if (actor.initial) "id:${actor.id}" else "class:${actor.scriptClass}"
        val limit = limits[map to key] ?: return false
        return damage in 1..limit
    }
}
