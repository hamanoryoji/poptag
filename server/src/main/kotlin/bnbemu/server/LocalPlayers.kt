package bnbemu.server

import bnbemu.netty.ClientSocket
import bnbemu.packet.RosterEntry
import bnbemu.packet.SessionEntry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.Locale
import java.util.Properties

data class LocalPlayerRecord(val accountKey: String, val name: String, val userId: Int)

/** Login history, not an online-presence list. No passwords or sample accounts. */
class LocalPlayerHistory(private val path: Path) {
    private val records = linkedMapOf<String,LocalPlayerRecord>()
    init {
        if (Files.exists(path)) {
            val p=Properties().apply { Files.newInputStream(path).use { load(it) } }
            repeat(p.getProperty("count","0").toInt()) { i ->
                val entry=LocalPlayerRecord(p.getProperty("$i.account"),p.getProperty("$i.name"),p.getProperty("$i.id").toInt())
                records[entry.accountKey.lowercase(Locale.ROOT)]=entry
            }
        }
    }
    @Synchronized fun record(entry: LocalPlayerRecord) {
        require(entry.accountKey.isNotBlank() && entry.name.isNotBlank())
        val key=entry.accountKey.lowercase(Locale.ROOT)
        if(records[key]==entry) return
        val next=LinkedHashMap(records).apply { put(key,entry) }
        val p=Properties().apply {
            setProperty("count",next.size.toString())
            next.values.forEachIndexed { i,e ->
                setProperty("$i.account",e.accountKey);setProperty("$i.name",e.name);setProperty("$i.id",e.userId.toString())
            }
        }
        Files.createDirectories(path.parent)
        val temporary=Files.createTempFile(path.parent,"players-",".tmp")
        try {
            Files.newOutputStream(temporary).use { p.store(it,"Local login history; not online status") }
            try { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING) }
            records.clear();records.putAll(next)
        } finally { Files.deleteIfExists(temporary) }
    }
    @Synchronized fun list(): List<LocalPlayerRecord> = records.values.toList()
}

object LocalPlayers {
    private val history=LocalPlayerHistory(Path.of(System.getProperty("bnbemu.accountsDir","data/local-accounts"),"login-history","players.properties"))
    fun remember(user: User) = history.record(LocalPlayerRecord(user.accountKey,user.name,user.id))
    fun entries(c: ClientSocket): List<LocalPlayerRecord> {
        c.users.forEach(::remember)
        val current=c.users.map { it.accountKey.lowercase(Locale.ROOT) }.toSet()
        // CUserMap::ReadList (4E086B) has eleven entries per page, although
        // its count is a byte. Retain all history on disk; never overrun that
        // native first-page buffer. Pagination belongs to the multiplayer work.
        return history.list().sortedBy { if(it.accountKey.lowercase(Locale.ROOT) in current) 0 else 1 }.take(11)
    }
    fun sessionEntries(c: ClientSocket): List<SessionEntry> = entries(c).map { e ->
        val level=c.users.firstOrNull { it.accountKey.equals(e.accountKey,true) }?.level
            ?: LocalEconomy.progress(LocalShop.store.account(e.accountKey).totalExperience).first
        SessionEntry(e.name,level,listItemBg=localNamePlateStyle(LocalShop.store.account(e.accountKey)))
    }
    fun rosterEntries(c: ClientSocket): List<RosterEntry> = entries(c).map { RosterEntry(it.name,it.userId) }
}
