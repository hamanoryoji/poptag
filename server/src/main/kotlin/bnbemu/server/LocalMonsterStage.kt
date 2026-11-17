package bnbemu.server

data class LocalMonster(val id: Int, val type: String, val hp: Int, val initial: Boolean, val boss: Boolean, val scriptClass: Int = id, val angerHp: Int = 0) {
    var influencesClear: Boolean = true
    var settlesOnDefeat: Boolean = false
}
data class LocalMonsterStage(val name: String, val next: String?, val monsters: Map<Int, LocalMonster>) {
    val objectives: Set<Int> get() = monsters.values.filter { it.initial && it.influencesClear && (if (monsters.values.any { m -> m.boss && m.influencesClear }) it.boss else true) }.map { it.id }.toSet()
}
object LocalMonsterStages {
    val stages: Map<String, LocalMonsterStage> by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/data/local-monster-stages.tsv"))
        val rows = stream.bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }.map { it.split('\t') }
        rows.groupBy { it[0] }.mapValues { (name, records) ->
            // The client allocates initial objects densely, skipping dormant
            // script templates. Template positions are not network object ids.
            val nativeOrder = records.sortedBy { it[5] != "1" }
            LocalMonsterStage(name, records.first()[1].takeUnless { it == "-" }, nativeOrder.mapIndexed { id, it ->
                id to LocalMonster(id, it[3], it[4].toInt().also { hp -> require(hp > 0) }, it[5] == "1", it[6] == "1", it.getOrNull(7)?.toInt() ?: id, it.getOrNull(8)?.toInt() ?: 0).apply {
                    influencesClear = it.getOrNull(9) != "0"
                    settlesOnDefeat = it.getOrNull(10) == "1"
                }
            }.toMap())
        }
    }
}
