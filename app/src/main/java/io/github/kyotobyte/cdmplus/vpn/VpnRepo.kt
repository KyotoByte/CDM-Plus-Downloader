package io.github.kyotobyte.cdmplus.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

data class VpnConfig(
    val upstream: Upstream = Upstream.CLOUDFLARE,
    val lists: Set<String> = setOf(BlockSource.STEVENBLACK.id, BlockSource.OISD.id),
    val allow: String = "",
    val deny: String = "",
    val encryptDownloads: Boolean = true,
    val wanted: Boolean = false, // user switched it on (used to restart after reboot)
)

data class ListInfo(val count: Int = 0, val updatedAt: Long = 0, val error: String? = null, val busy: Boolean = false)

/** Settings, downloaded block lists and live stats for the 1.1.1.1 mode. */
class VpnRepo(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("cdm_vpn", Context.MODE_PRIVATE)
    private val dir = File(ctx.filesDir, "blocklists").apply { mkdirs() }

    private val _config = MutableStateFlow(read())
    val config: StateFlow<VpnConfig> = _config.asStateFlow()
    private val _lists = MutableStateFlow(BlockSource.entries.associate { it.id to readInfo(it) })
    val lists: StateFlow<Map<String, ListInfo>> = _lists.asStateFlow()
    val running = MutableStateFlow(false)
    val stats = DnsStats()

    /** Shared by the tunnel and the in-app browser's ad-block. */
    @Volatile var filter: DnsFilter = DnsFilter.NONE
        private set

    val lifetimeBlocked: Long get() = prefs.getLong("life_blocked", 0) + stats.blocked.get()

    private fun read() = VpnConfig(
        upstream = Upstream.byId(prefs.getString("upstream", null)),
        lists = prefs.getStringSet("lists", null) ?: VpnConfig().lists,
        allow = prefs.getString("allow", "") ?: "",
        deny = prefs.getString("deny", "") ?: "",
        encryptDownloads = prefs.getBoolean("enc_dl", true),
        wanted = prefs.getBoolean("wanted", false),
    )

    private fun readInfo(s: BlockSource) = ListInfo(prefs.getInt("n_${s.id}", 0), prefs.getLong("t_${s.id}", 0))

    fun update(f: (VpnConfig) -> VpnConfig) {
        val c = f(_config.value)
        prefs.edit().putString("upstream", c.upstream.id).putStringSet("lists", c.lists)
            .putString("allow", c.allow).putString("deny", c.deny).putBoolean("enc_dl", c.encryptDownloads)
            .putBoolean("wanted", c.wanted).apply()
        val listsChanged = c.lists != _config.value.lists || c.allow != _config.value.allow || c.deny != _config.value.deny
        _config.value = c
        if (listsChanged) rebuildFilter()
    }

    fun saveLifetime() {
        prefs.edit().putLong("life_blocked", prefs.getLong("life_blocked", 0) + stats.blocked.getAndSet(0)).apply()
    }

    /** Builds the combined filter from the switched-on lists + the user's own allow/deny lists. */
    fun rebuildFilter(): DnsFilter {
        val c = _config.value
        val domains = ArrayList<String>()
        for (s in BlockSource.entries) if (s.id in c.lists) {
            val f = File(dir, "${s.id}.txt")
            if (f.exists()) f.bufferedReader().useLines { seq -> seq.forEach { if (it.isNotEmpty()) domains += it } }
        }
        filter = DnsFilter(Blocklist.of(domains), DnsFilter.userSet(c.allow), DnsFilter.userSet(c.deny))
        return filter
    }

    /** Downloads one list now (in-app update). Returns the domain count. */
    suspend fun updateList(s: BlockSource, client: OkHttpClient): Result<Int> = withContext(Dispatchers.IO) {
        setInfo(s) { it.copy(busy = true, error = null) }
        runCatching {
            val body = client.newCall(Request.Builder().url(s.url).header("User-Agent", "CDMPlus/1.1 (Android)").build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                r.body!!.string()
            }
            val domains = ListParser.parse(body)
            if (domains.size < 100) error("list looks empty")
            File(dir, "${s.id}.tmp").apply { writeText(domains.joinToString("\n")) }.renameTo(File(dir, "${s.id}.txt"))
            prefs.edit().putInt("n_${s.id}", domains.size).putLong("t_${s.id}", System.currentTimeMillis()).apply()
            setInfo(s) { ListInfo(domains.size, System.currentTimeMillis()) }
            rebuildFilter()
            domains.size
        }.onFailure { e -> setInfo(s) { it.copy(busy = false, error = e.message ?: "failed") } }
    }

    suspend fun updateMissingOrStale(client: OkHttpClient, maxAgeMs: Long = 7L * 24 * 3600 * 1000) {
        for (s in BlockSource.entries) if (s.id in _config.value.lists) {
            val i = _lists.value[s.id]
            if (i == null || i.count == 0 || System.currentTimeMillis() - i.updatedAt > maxAgeMs) updateList(s, client)
        }
    }

    private fun setInfo(s: BlockSource, f: (ListInfo) -> ListInfo) {
        _lists.value = _lists.value.toMutableMap().also { it[s.id] = f(it[s.id] ?: ListInfo()) }
    }
}
