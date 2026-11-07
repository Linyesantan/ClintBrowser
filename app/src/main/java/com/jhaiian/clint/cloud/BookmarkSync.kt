package com.jhaiian.clint.cloud

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jhaiian.clint.R
import com.jhaiian.clint.bookmarks.Bookmark
import com.jhaiian.clint.bookmarks.BookmarkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

enum class BookmarkSyncOutcome {
    SUCCESS,
    NOT_CONFIGURED,
    AUTH_FAILED,
    NETWORK_ERROR,
    SERVER_ERROR,
    LOCKED,
    PARSE_ERROR,
    MASS_DELETE,
    FAILED
}

data class BookmarkSyncReport(
    val outcome: BookmarkSyncOutcome,
    val localAdded: Int = 0,
    val localRemoved: Int = 0,
    val localChanged: Int = 0,
    val remoteUpdated: Boolean = false,
    val httpCode: Int = 0,
    val detail: String? = null,
    val finishedAt: Long = System.currentTimeMillis()
)

/**
 * Two-way bookmark sync with a Floccus compatible XBEL file on the WebDAV server configured for
 * cloud backup.
 *
 * Each run does a three-way merge between the local bookmarks, the remote file and the state both
 * sides had after the last successful sync (the "base", kept in app storage):
 * - a change made on only one side wins;
 * - an edit beats a delete;
 * - if both sides changed the same field differently, the phone wins;
 * - the very first sync (no base) only adds, it never deletes anything on either side.
 * A run that would delete more than half of either side is refused as a safety stop.
 */
object BookmarkSync {
    const val DEFAULT_PATH = "bookmarks.xbel"

    private const val KEY_ENABLED = "bookmark_sync_enabled"
    private const val KEY_PATH = "bookmark_sync_path"
    private const val KEY_LAST_TIME = "bookmark_sync_last_time"
    private const val KEY_LAST_OUTCOME = "bookmark_sync_last_outcome"
    private const val KEY_LAST_ADDED = "bookmark_sync_last_added"
    private const val KEY_LAST_REMOVED = "bookmark_sync_last_removed"
    private const val KEY_LAST_CHANGED = "bookmark_sync_last_changed"
    private const val KEY_LAST_REMOTE = "bookmark_sync_last_remote"
    private const val KEY_LAST_HTTP = "bookmark_sync_last_http"
    private const val KEY_LAST_DETAIL = "bookmark_sync_last_detail"

    private const val BASE_FILE = "bookmark_sync_base.json"
    private const val LOCK_STALE_MS = 15L * 60L * 1000L
    private const val MASS_DELETE_MIN = 10

    private val runLock = Mutex()

    /** True while a sync is writing to the local database, so those writes do not schedule another sync. */
    @Volatile var applying: Boolean = false
        private set

    // ---- settings -------------------------------------------------------------------------

    fun isEnabled(context: Context): Boolean = WebDavSettings.prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        WebDavSettings.prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun path(context: Context): String =
        (WebDavSettings.prefs(context).getString(KEY_PATH, DEFAULT_PATH) ?: DEFAULT_PATH).ifBlank { DEFAULT_PATH }

    fun savePath(context: Context, value: String) {
        WebDavSettings.prefs(context).edit().putString(KEY_PATH, value.trim().trimStart('/')).apply()
    }

    /** Forgets the last synced state; the next sync then only adds on both sides, like a first sync. */
    fun resetBase(context: Context) {
        File(context.applicationContext.filesDir, BASE_FILE).delete()
    }

    // ---- running ---------------------------------------------------------------------------

    suspend fun run(context: Context): BookmarkSyncReport = runLock.withLock {
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            try {
                // Retry when the file changed between our read and write (HTTP 412); the last try drops the conditions.
                var report: BookmarkSyncReport? = null
                for (attempt in 1..3) {
                    report = runOnce(app, useConditions = attempt <= 2)
                    if (report != null) break
                }
                report ?: BookmarkSyncReport(BookmarkSyncOutcome.SERVER_ERROR, httpCode = 412)
            } catch (e: WebDavException) {
                reportFor(e)
            } catch (e: XbelParseException) {
                BookmarkSyncReport(BookmarkSyncOutcome.PARSE_ERROR, detail = e.message)
            } catch (e: Exception) {
                BookmarkSyncReport(BookmarkSyncOutcome.FAILED, detail = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** One sync pass. Returns null when the remote file changed under us (HTTP 412) and the pass should be repeated. */
    private fun runOnce(context: Context, useConditions: Boolean): BookmarkSyncReport? {
        val config = WebDavSettings.load(context)
        if (!config.isComplete) return BookmarkSyncReport(BookmarkSyncOutcome.NOT_CONFIGURED)
        val path = path(context)
        val client = try {
            WebDavClient(config)
        } catch (e: WebDavException) {
            return BookmarkSyncReport(BookmarkSyncOutcome.NOT_CONFIGURED)
        }

        // Respect the lock file Floccus writes while it syncs, and hold it ourselves meanwhile.
        val lockPath = "$path.lock"
        val lock = client.stat(lockPath)
        if (lock != null && lock.lastModified > 0 && System.currentTimeMillis() - lock.lastModified < LOCK_STALE_MS) {
            return BookmarkSyncReport(BookmarkSyncOutcome.LOCKED)
        }
        val parent = path.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) client.ensureDirectory("$parent/")
        client.putText(lockPath, "{\"client\":\"Clint\",\"time\":${System.currentTimeMillis()}}", contentType = "application/json")
        try {
            return syncLocked(context, client, config, path, useConditions)
        } finally {
            runCatching { client.deletePath(lockPath) }
        }
    }

    private fun syncLocked(
        context: Context,
        client: WebDavClient,
        config: WebDavSettings.Config,
        path: String,
        useConditions: Boolean
    ): BookmarkSyncReport? {
        val baseKey = "${config.serverUrl.trim()}|${config.username.trim()}|$path"
        val remoteText = client.getText(path)
        val remote = if (remoteText == null) RemoteXbel.EMPTY else BookmarkXbel.parse(remoteText.body)
        // If the remote file is gone, start over instead of reading that as "everything was deleted".
        val base = if (remoteText == null) null else loadBase(context, baseKey)
        val local = readLocal(context)

        val merged = merge(base, local, remote.tree)

        // Safety stop against wiping one side by accident.
        if (base != null) {
            val localDeletes = local.bookmarks.keys.count { it !in merged.bookmarks }
            val remoteDeletes = remote.tree.bookmarks.keys.count { it !in merged.bookmarks }
            if ((localDeletes >= MASS_DELETE_MIN && localDeletes * 2 > local.bookmarks.size) ||
                (remoteDeletes >= MASS_DELETE_MIN && remoteDeletes * 2 > remote.tree.bookmarks.size)
            ) {
                return BookmarkSyncReport(BookmarkSyncOutcome.MASS_DELETE, detail = "${maxOf(localDeletes, remoteDeletes)}")
            }
        }

        // Upload first: if the server rejects it nothing changes locally.
        val remoteChanged = if (remoteText == null) {
            merged.bookmarks.isNotEmpty() || merged.folders.isNotEmpty()
        } else {
            !sameTree(merged, remote.tree)
        }
        if (remoteChanged) {
            val xml = BookmarkXbel.serialize(merged, remote)
            val etag = remoteText?.etag?.takeIf { useConditions && !it.startsWith("W/") }
            val ok = client.putText(
                path,
                xml,
                ifMatch = etag,
                ifNoneMatchAny = useConditions && remoteText == null
            )
            if (!ok) return null
        }

        val counts = applyLocal(context, local, merged)
        saveBase(context, baseKey, merged)
        return BookmarkSyncReport(
            BookmarkSyncOutcome.SUCCESS,
            localAdded = counts[0],
            localRemoved = counts[1],
            localChanged = counts[2],
            remoteUpdated = remoteChanged
        )
    }

    // ---- merge -----------------------------------------------------------------------------

    private fun <T> pick(base: T?, local: T?, remote: T?, hasBase: Boolean): T? {
        if (local == remote) return local
        if (!hasBase) return local ?: remote
        if (local == base) return remote
        if (remote == base) return local
        return local ?: remote
    }

    internal fun merge(base: SyncTree?, local: SyncTree, remote: SyncTree): SyncTree {
        val hasBase = base != null
        val result = LinkedHashMap<String, SyncBookmark>()
        val urls = LinkedHashSet<String>().apply {
            addAll(remote.bookmarks.keys); addAll(local.bookmarks.keys); base?.let { addAll(it.bookmarks.keys) }
        }
        for (url in urls) {
            val b = base?.bookmarks?.get(url)
            val l = local.bookmarks[url]
            val r = remote.bookmarks[url]
            val merged: SyncBookmark? = when {
                l != null && r != null -> {
                    val title = pick(b?.title, l.title, r.title, b != null) ?: l.title
                    val path = pick(b?.path, l.path, r.path, b != null) ?: l.path
                    SyncBookmark(url, title, path)
                }
                l == null && r == null -> null
                else -> {
                    val present = l ?: r!!
                    when {
                        !hasBase || b == null -> present          // newly added on one side
                        present == b -> null                      // unchanged here, deleted there
                        else -> present                           // edited here, deleted there: keep the edit
                    }
                }
            }
            if (merged != null) result[url] = merged
        }

        val folders = LinkedHashSet<List<String>>()
        val allPaths = LinkedHashSet<List<String>>().apply {
            addAll(remote.folders); addAll(local.folders); base?.let { addAll(it.folders) }
        }
        for (p in allPaths) {
            val keep = pick(
                base?.folders?.contains(p),
                local.folders.contains(p),
                remote.folders.contains(p),
                hasBase
            ) ?: false
            if (keep) folders.add(p)
        }
        // A folder that still holds something survives, along with its ancestors.
        fun addAncestors(p: List<String>) { for (i in 1..p.size) folders.add(p.subList(0, i).toList()) }
        result.values.forEach { addAncestors(it.path) }
        folders.toList().forEach { addAncestors(it) }
        return SyncTree(result, folders)
    }

    private fun sameTree(a: SyncTree, b: SyncTree): Boolean {
        if (a.bookmarks != b.bookmarks) return false
        val fa = a.folders.toHashSet()
        val fb = b.folders.toHashSet()
        return fa == fb
    }

    // ---- local database --------------------------------------------------------------------

    private fun readLocal(context: Context): SyncTree {
        val folders = BookmarkManager.getAllFoldersFlat(context)
        val byId = folders.associateBy { it.id }
        val pathCache = HashMap<Long, List<String>>()
        fun pathOf(id: Long?): List<String> {
            if (id == null) return emptyList()
            pathCache[id]?.let { return it }
            val names = ArrayList<String>()
            var current = byId[id]
            val seen = HashSet<Long>()
            while (current != null && seen.add(current.id)) {
                names.add(current.name)
                current = current.parentId?.let { byId[it] }
            }
            return names.reversed().also { pathCache[id] = it }
        }
        val folderPaths = LinkedHashSet<List<String>>()
        folders.forEach { folderPaths.add(pathOf(it.id)) }
        val bookmarks = LinkedHashMap<String, SyncBookmark>()
        BookmarkManager.getAll(context).forEach { bm ->
            if (bm.url.isNotBlank()) bookmarks[bm.url] = SyncBookmark(bm.url, bm.title, pathOf(bm.folderId))
        }
        return SyncTree(bookmarks, folderPaths)
    }

    /** Makes the local database match [target]. Returns [added, removed, changed]. */
    private fun applyLocal(context: Context, local: SyncTree, target: SyncTree): IntArray {
        var added = 0
        var removed = 0
        var changed = 0
        applying = true
        try {
            val folders = BookmarkManager.getAllFoldersFlat(context)
            val byId = folders.associateBy { it.id }
            fun pathOf(id: Long): List<String> {
                val names = ArrayList<String>()
                var current = byId[id]
                val seen = HashSet<Long>()
                while (current != null && seen.add(current.id)) {
                    names.add(current.name)
                    current = current.parentId?.let { byId[it] }
                }
                return names.reversed()
            }
            val idByPath = HashMap<List<String>, Long>()
            folders.sortedBy { it.id }.forEach { f -> idByPath.putIfAbsent(pathOf(f.id), f.id) }

            fun ensureFolder(path: List<String>): Long? {
                if (path.isEmpty()) return null
                idByPath[path]?.let { return it }
                val parentId = ensureFolder(path.dropLast(1))
                val id = BookmarkManager.createFolder(context, path.last(), parentId)
                idByPath[path] = id
                return id
            }
            target.folders.sortedBy { it.size }.forEach { ensureFolder(it) }

            for ((url, bm) in target.bookmarks) {
                val existing = local.bookmarks[url]
                if (existing == null) {
                    BookmarkManager.restoreBookmark(
                        context,
                        Bookmark(url = url, title = bm.title, addedAt = System.currentTimeMillis(), folderId = ensureFolder(bm.path))
                    )
                    added++
                } else {
                    var touched = false
                    if (existing.title != bm.title) {
                        BookmarkManager.updateTitle(context, url, bm.title)
                        touched = true
                    }
                    if (existing.path != bm.path) {
                        BookmarkManager.moveBookmark(context, url, ensureFolder(bm.path))
                        touched = true
                    }
                    if (touched) changed++
                }
            }
            for (url in local.bookmarks.keys) {
                if (url !in target.bookmarks) {
                    BookmarkManager.remove(context, url)
                    removed++
                }
            }
            // Remove folders that are gone, deepest first. Their bookmarks were moved or removed above.
            folders.map { it.id to pathOf(it.id) }
                .filter { (_, p) -> p !in target.folders }
                .sortedByDescending { it.second.size }
                .forEach { (id, _) -> BookmarkManager.deleteFolderRecursive(context, id) }
        } finally {
            applying = false
        }
        return intArrayOf(added, removed, changed)
    }

    // ---- base snapshot ---------------------------------------------------------------------

    private fun loadBase(context: Context, key: String): SyncTree? = runCatching {
        val file = File(context.filesDir, BASE_FILE)
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        if (json.optString("key") != key) return null
        val bookmarks = LinkedHashMap<String, SyncBookmark>()
        val arr = json.getJSONArray("bookmarks")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val url = o.getString("u")
            bookmarks[url] = SyncBookmark(url, o.optString("t", ""), jsonPath(o.getJSONArray("p")))
        }
        val folders = LinkedHashSet<List<String>>()
        val farr = json.getJSONArray("folders")
        for (i in 0 until farr.length()) folders.add(jsonPath(farr.getJSONArray(i)))
        SyncTree(bookmarks, folders)
    }.getOrNull()

    private fun jsonPath(arr: JSONArray): List<String> = List(arr.length()) { arr.getString(it) }

    private fun saveBase(context: Context, key: String, tree: SyncTree) {
        val json = JSONObject()
        json.put("key", key)
        val arr = JSONArray()
        tree.bookmarks.values.forEach { bm ->
            arr.put(JSONObject().put("u", bm.url).put("t", bm.title).put("p", JSONArray(bm.path)))
        }
        json.put("bookmarks", arr)
        val farr = JSONArray()
        tree.folders.forEach { farr.put(JSONArray(it)) }
        json.put("folders", farr)
        val file = File(context.filesDir, BASE_FILE)
        val tmp = File(context.filesDir, "$BASE_FILE.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(json.toString())
            tmp.delete()
        }
    }

    // ---- status ----------------------------------------------------------------------------

    fun reportFor(e: WebDavException): BookmarkSyncReport = when (e.kind) {
        WebDavException.Kind.AUTH -> BookmarkSyncReport(BookmarkSyncOutcome.AUTH_FAILED, httpCode = e.httpCode)
        WebDavException.Kind.NETWORK -> BookmarkSyncReport(BookmarkSyncOutcome.NETWORK_ERROR, detail = e.detail)
        WebDavException.Kind.SERVER -> BookmarkSyncReport(BookmarkSyncOutcome.SERVER_ERROR, httpCode = e.httpCode, detail = e.detail)
        WebDavException.Kind.CONFIG -> BookmarkSyncReport(BookmarkSyncOutcome.NOT_CONFIGURED)
    }

    fun recordStatus(context: Context, report: BookmarkSyncReport) {
        WebDavSettings.prefs(context).edit()
            .putLong(KEY_LAST_TIME, report.finishedAt)
            .putString(KEY_LAST_OUTCOME, report.outcome.name)
            .putInt(KEY_LAST_ADDED, report.localAdded)
            .putInt(KEY_LAST_REMOVED, report.localRemoved)
            .putInt(KEY_LAST_CHANGED, report.localChanged)
            .putBoolean(KEY_LAST_REMOTE, report.remoteUpdated)
            .putInt(KEY_LAST_HTTP, report.httpCode)
            .putString(KEY_LAST_DETAIL, report.detail)
            .apply()
    }

    fun lastReport(context: Context): BookmarkSyncReport? {
        val p = WebDavSettings.prefs(context)
        val time = p.getLong(KEY_LAST_TIME, 0L)
        if (time == 0L) return null
        val outcome = runCatching { BookmarkSyncOutcome.valueOf(p.getString(KEY_LAST_OUTCOME, "") ?: "") }.getOrNull() ?: return null
        return BookmarkSyncReport(
            outcome = outcome,
            localAdded = p.getInt(KEY_LAST_ADDED, 0),
            localRemoved = p.getInt(KEY_LAST_REMOVED, 0),
            localChanged = p.getInt(KEY_LAST_CHANGED, 0),
            remoteUpdated = p.getBoolean(KEY_LAST_REMOTE, false),
            httpCode = p.getInt(KEY_LAST_HTTP, 0),
            detail = p.getString(KEY_LAST_DETAIL, null),
            finishedAt = time
        )
    }

    fun message(context: Context, report: BookmarkSyncReport): String = when (report.outcome) {
        BookmarkSyncOutcome.SUCCESS -> context.getString(
            R.string.bookmark_sync_msg_success,
            report.localAdded, report.localRemoved, report.localChanged,
            context.getString(if (report.remoteUpdated) R.string.bookmark_sync_remote_updated else R.string.bookmark_sync_remote_same)
        )
        BookmarkSyncOutcome.NOT_CONFIGURED -> context.getString(R.string.cloud_backup_msg_not_configured)
        BookmarkSyncOutcome.AUTH_FAILED ->
            CloudBackupFeedback.message(context, CloudBackupOutcome.AUTH_FAILED, 0L, report.httpCode, report.detail)
        BookmarkSyncOutcome.NETWORK_ERROR ->
            CloudBackupFeedback.message(context, CloudBackupOutcome.NETWORK_ERROR, 0L, report.httpCode, report.detail)
        BookmarkSyncOutcome.SERVER_ERROR ->
            CloudBackupFeedback.message(context, CloudBackupOutcome.SERVER_ERROR, 0L, report.httpCode, report.detail)
        BookmarkSyncOutcome.LOCKED -> context.getString(R.string.bookmark_sync_msg_locked)
        BookmarkSyncOutcome.PARSE_ERROR -> context.getString(R.string.bookmark_sync_msg_parse_error, report.detail ?: "")
        BookmarkSyncOutcome.MASS_DELETE -> context.getString(R.string.bookmark_sync_msg_mass_delete, report.detail ?: "")
        BookmarkSyncOutcome.FAILED -> context.getString(R.string.bookmark_sync_msg_failed, report.detail ?: "")
    }
}

class BookmarkSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!BookmarkSync.isEnabled(applicationContext) && !inputData.getBoolean(KEY_MANUAL, false)) {
            return Result.success()
        }
        val report = BookmarkSync.run(applicationContext)
        BookmarkSync.recordStatus(applicationContext, report)
        if (inputData.getBoolean(KEY_MANUAL, false)) {
            val message = BookmarkSync.message(applicationContext, report)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
            }
        }
        return Result.success(workDataOf(KEY_OUTCOME to report.outcome.name))
    }

    companion object {
        const val KEY_MANUAL = "manual"
        const val KEY_OUTCOME = "outcome"
    }
}

object BookmarkSyncScheduler {
    const val PERIODIC_WORK = "clint_bookmark_sync_periodic"
    const val CHANGE_WORK = "clint_bookmark_sync_change"
    const val NOW_WORK = "clint_bookmark_sync_now"

    private val networkConstraint = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Applies the saved switch: when on, syncs every 30 minutes and shortly after the app starts; when off, cancels both. */
    fun apply(context: Context, syncSoon: Boolean = true) {
        val wm = WorkManager.getInstance(context)
        if (BookmarkSync.isEnabled(context)) {
            val periodic = PeriodicWorkRequestBuilder<BookmarkSyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(networkConstraint)
                .addTag(PERIODIC_WORK)
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, periodic)
            if (syncSoon) enqueueChange(context, delaySeconds = 5, policy = ExistingWorkPolicy.KEEP)
        } else {
            wm.cancelUniqueWork(PERIODIC_WORK)
            wm.cancelUniqueWork(CHANGE_WORK)
        }
    }

    /** Called after every local bookmark edit: syncs 15 seconds after the last edit. */
    fun onLocalChange(context: Context) {
        if (BookmarkSync.applying || !BookmarkSync.isEnabled(context)) return
        enqueueChange(context, delaySeconds = 15, policy = ExistingWorkPolicy.REPLACE)
    }

    private fun enqueueChange(context: Context, delaySeconds: Long, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<BookmarkSyncWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setConstraints(networkConstraint)
            .addTag(CHANGE_WORK)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(CHANGE_WORK, policy, request)
    }

    /** Manual "sync now": runs right away and shows the result as a toast. */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<BookmarkSyncWorker>()
            .setInputData(workDataOf(BookmarkSyncWorker.KEY_MANUAL to true))
            .addTag(NOW_WORK)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW_WORK, ExistingWorkPolicy.KEEP, request)
    }
}
