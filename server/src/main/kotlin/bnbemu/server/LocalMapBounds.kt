package bnbemu.server

data class LocalMapBounds(val width: Int, val height: Int) {
    fun contains(x: Int, y: Int) = x in 0 until width && y in 0 until height

    companion object {
        private val boards: Map<String, LocalMapBounds> by lazy {
            checkNotNull(javaClass.getResourceAsStream("/data/local-map-dimensions.tsv"))
                .bufferedReader().useLines { lines ->
                    lines.filter { it.isNotBlank() }.associate { line ->
                        val fields = line.split('\t')
                        fields[0] to LocalMapBounds(fields[1].toInt(), fields[2].toInt())
                    }
                }
        }
        fun forBoard(name: String): LocalMapBounds? = boards[name]
    }
}
