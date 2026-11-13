package com.takahashirinta.ncrust.playlist

import android.content.Context
import com.takahashirinta.ncrust.cache.ContentCache
import com.takahashirinta.ncrust.network.RetrofitClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

enum class PlaylistPanelMode { ADD, CREATE, EDIT, REMOVE, DELETE, REORDER, SUBSCRIBE, UNSUBSCRIBE }
data class PlaylistPanelRequest(val mode: PlaylistPanelMode, val songIds: List<Long> = emptyList(), val playlistId: Long = 0)
/**
 * [background] = 任务在后台跑、面板不占屏：已经在页内确认过的动作（移除、保存顺序、从选择器加入）
 * 不再弹第二个全屏面板。进度与结果由 [PlaylistManager.notice] 的轻提示承接；只有待确认、
 * 部分完成和失败才把面板调出来，让用户核对。
 */
data class PlaylistPanelState(
    val visible: Boolean = false, val request: PlaylistPanelRequest? = null,
    val loading: Boolean = false, val busy: Boolean = false, val ownerId: Long = 0,
    val collection: PlaylistCollection = PlaylistCollection(emptyList(), false),
    val snapshot: PlaylistSnapshot? = null,
    val draft: PlaylistDraft = PlaylistDraft("", "", 10),
    val problem: PlaylistProblem? = null, val result: PlaylistResult? = null,
    val backupFile: File? = null,
    val background: Boolean = false, val targetName: String = ""
) {
    /** 后台任务进行中（含读取上下文阶段）；页面据此禁用同一歌单上的写入口。 */
    val working: Boolean get() = background && (loading || busy)
}

enum class PlaylistNoticeKind { ADDED, REMOVED, REORDERED, CREATED, SAVED, DELETED, SUBSCRIBED, UNSUBSCRIBED }

/** 轻提示只携带类型和数字，文案由界面按当前语言生成；[serial] 让相同内容的两次提示也能各自出现。 */
data class PlaylistNotice(
    val serial: Long, val kind: PlaylistNoticeKind,
    val count: Int = 0, val name: String = "", val playlistId: Long? = null
)

/** 当前账号的「我的歌单」列表（自建 + 收藏），供各处判断某个歌单是否已收藏。 */
data class PlaylistLibrary(val ownerId: Long, val collection: PlaylistCollection)

/** 批量删除的进度与汇总。逐个歌单走完整的「备份 → 提交 → 读回」，不并发、不合并请求。 */
data class PlaylistBatch(
    val visible: Boolean = false, val running: Boolean = false,
    val total: Int = 0, val done: Int = 0, val deleted: Int = 0, val failed: Int = 0,
    val problem: PlaylistProblem? = null
)

/** 页面只观察任务；关闭面板或导航离开不会取消已经发出的写入。 */
object PlaylistManager {
    private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _enabled = MutableStateFlow(false)
    val enabled = _enabled.asStateFlow()
    private val _state = MutableStateFlow(PlaylistPanelState())
    val state = _state.asStateFlow()
    private val _batch = MutableStateFlow(PlaylistBatch())
    val batch = _batch.asStateFlow()
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    private val _notice = MutableStateFlow<PlaylistNotice?>(null)
    val notice = _notice.asStateFlow()
    private val _library = MutableStateFlow<PlaylistLibrary?>(null)
    val library = _library.asStateFlow()
    @Volatile private var noticeSerial = 0L
    private fun notify(kind: PlaylistNoticeKind, count: Int = 0, name: String = "", playlistId: Long? = null) {
        _notice.value = PlaylistNotice(++noticeSerial, kind, count, name, playlistId)
    }
    fun dismissNotice(serial: Long) { _notice.update { if (it?.serial == serial) null else it } }
    @Volatile private var cookie = ""
    @Volatile private var observedCookie = ""
    @Volatile private var generation = 0L
    private var pendingCommand: PlaylistCommand? = null
    private fun prefs() = app!!.getSharedPreferences("ncrust_settings", Context.MODE_PRIVATE)

    fun init(context: Context) {
        app = context.applicationContext
        observedCookie = RetrofitClient.getCookie().orEmpty()
        _enabled.value = prefs().getBoolean("playlist_management_enabled", false)
    }
    fun setEnabled(context: Context, value: Boolean) {
        if (app == null) init(context)
        prefs().edit().putBoolean("playlist_management_enabled", value).apply()
        _enabled.value = value
        if (!value) close()
    }
    fun accountChanged() {
        val now = RetrofitClient.getCookie().orEmpty()
        if (observedCookie != now) {
            observedCookie = now
            generation++
            cookie = ""
            pendingCommand = null
            _state.value = PlaylistPanelState()
            _library.value = null
            _revision.update { it + 1 }
        }
    }
    fun open(request: PlaylistPanelRequest) {
        if (!_enabled.value) return
        accountChanged()
        if (_state.value.busy || _state.value.result?.outcome in listOf(PlaylistOutcome.PENDING, PlaylistOutcome.PARTIAL)) {
            _state.update { it.copy(visible = true) }
            return
        }
        cookie = RetrofitClient.getCookie().orEmpty()
        val token = ++generation
        val credential = cookie
        _state.value = PlaylistPanelState(visible = true, request = request, loading = true)
        scope.launch {
            try {
                if (credential.isEmpty()) throw PlaylistRequestException(PlaylistProblem.LOGIN)
                val gateway = NetworkPlaylistGateway(credential)
                val uid = gateway.userId()
                if (!current(token, credential)) return@launch
                val restored = restore(uid)
                if (restored != null) {
                    pendingCommand = restored
                    publish(token, credential) { PlaylistPanelState(visible = true, request = requestFor(restored), ownerId = uid,
                        draft = restored.draft ?: PlaylistDraft("", "", 10),
                        result = PlaylistResult(PlaylistOutcome.PENDING, restored.playlistId.takeIf { it > 0 }, problem = PlaylistProblem.INCOMPLETE)) }
                    return@launch
                }
                val list = if (request.mode == PlaylistPanelMode.ADD) gateway.collection(uid) else PlaylistCollection(emptyList(), false)
                val snapshot = if (request.playlistId > 0) gateway.snapshot(request.playlistId) else null
                if (!current(token, credential)) return@launch
                publish(token, credential) { it.copy(loading = false, ownerId = uid, collection = list, snapshot = snapshot,
                    draft = snapshot?.let { s -> PlaylistDraft(s.info.name, s.description, s.info.privacy) } ?: it.draft,
                    problem = if (snapshot != null && !snapshot.info.isOwnedBy(uid)) PlaylistProblem.PERMISSION else null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                publish(token, credential) { it.copy(loading = false,
                    problem = (error as? PlaylistRequestException)?.problem ?: PlaylistProblem.NETWORK) }
            }
        }
    }
    /**
     * 已在页内确认过的动作直接执行：先读账号与目标快照，再提交，面板保持隐藏。
     * 有遗留待核对记录、读取失败或结果不是「已确认」时才调出面板，绝不静默吞掉。
     */
    fun perform(request: PlaylistPanelRequest) {
        if (!_enabled.value) return
        accountChanged()
        if (_state.value.busy || _state.value.loading || _state.value.result?.outcome in listOf(PlaylistOutcome.PENDING, PlaylistOutcome.PARTIAL)) {
            _state.update { it.copy(visible = true, background = false) }
            return
        }
        cookie = RetrofitClient.getCookie().orEmpty()
        val token = ++generation
        val credential = cookie
        _state.value = PlaylistPanelState(request = request, loading = true, background = true)
        scope.launch {
            try {
                if (credential.isEmpty()) throw PlaylistRequestException(PlaylistProblem.LOGIN)
                val gateway = NetworkPlaylistGateway(credential)
                val uid = gateway.userId()
                if (!current(token, credential)) return@launch
                restore(uid)?.let { restored ->
                    pendingCommand = restored
                    publish(token, credential) { PlaylistPanelState(visible = true, request = requestFor(restored), ownerId = uid,
                        draft = restored.draft ?: PlaylistDraft("", "", 10),
                        result = PlaylistResult(PlaylistOutcome.PENDING, restored.playlistId.takeIf { it > 0 }, problem = PlaylistProblem.INCOMPLETE)) }
                    return@launch
                }
                val snapshot = if (request.playlistId > 0) gateway.snapshot(request.playlistId) else null
                if (!current(token, credential)) return@launch
                // 收藏 / 取消收藏只对他人歌单；其余写入只对本人歌单。
                val subscription = request.mode == PlaylistPanelMode.SUBSCRIBE || request.mode == PlaylistPanelMode.UNSUBSCRIBE
                if (snapshot != null && snapshot.info.isOwnedBy(uid) == subscription) throw PlaylistRequestException(PlaylistProblem.PERMISSION)
                publish(token, credential) { it.copy(loading = false, ownerId = uid, snapshot = snapshot,
                    targetName = snapshot?.info?.name.orEmpty()) }
                if (current(token, credential)) submit()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                publish(token, credential) { it.copy(loading = false, background = false, visible = true,
                    problem = (error as? PlaylistRequestException)?.problem ?: PlaylistProblem.NETWORK) }
            }
        }
    }

    /** 选择器里点中目标后立即收起面板，写入转到后台，完成后由轻提示报告。 */
    fun submitInBackground(targetId: Long) {
        val state = _state.value
        if (state.busy || state.loading || state.result != null) return
        val name = state.collection.items.firstOrNull { it.id == targetId }?.name.orEmpty()
        _state.update { it.copy(background = true, visible = false, targetName = name) }
        submit(targetId)
    }

    fun updateDraft(change: (PlaylistDraft) -> PlaylistDraft) {
        _state.update { if (it.busy) it else it.copy(draft = change(it.draft)) }
    }
    fun createFromPicker() {
        if (_state.value.busy) return
        _state.update { it.copy(request = PlaylistPanelRequest(PlaylistPanelMode.CREATE, it.request?.songIds.orEmpty()),
            draft = PlaylistDraft("", "", 10), problem = null) }
    }
    fun close() { _state.update { it.copy(visible = false) } }
    fun finish() {
        if (_state.value.busy) return
        clearPending(_state.value.ownerId)
        pendingCommand = null
        _state.value = PlaylistPanelState()
    }

    /**
     * 批量删除歌单。**逐个**执行完整的「备份 → 提交 → 读回」：没有批量删除端点，
     * `playlist/remove` 只是从「我的歌单」移出（对非本人歌单还返回 200 却不做事），
     * 不能拿它冒充删除。失败项只统计不重试，结果里保留第一个失败原因。
     */
    fun deletePlaylists(ids: List<Long>) {
        if (!_enabled.value || ids.isEmpty() || _batch.value.running) return
        accountChanged()
        if (app == null) return
        cookie = RetrofitClient.getCookie().orEmpty()
        val credential = cookie
        val token = ++generation
        _batch.value = PlaylistBatch(visible = true, running = true, total = ids.size)
        scope.launch {
            fun fail(problem: PlaylistProblem) {
                _batch.update { it.copy(running = false, problem = problem, failed = it.total) }
            }
            if (credential.isEmpty()) { fail(PlaylistProblem.LOGIN); return@launch }
            val gateway = NetworkPlaylistGateway(credential)
            val uid = try { gateway.userId() } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { 0L }
            if (uid <= 0) { fail(PlaylistProblem.LOGIN); return@launch }
            val repository = PlaylistRepository(gateway,
                accountCurrent = { current(token, credential) },
                backupSnapshot = { snapshot -> backup(uid, snapshot) })
            var deleted = 0
            var failed = 0
            var problem: PlaylistProblem? = null
            for ((index, id) in ids.withIndex()) {
                if (!current(token, credential)) break
                val result = try {
                    repository.execute(PlaylistCommand(PlaylistAction.DELETE, id))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (result?.outcome == PlaylistOutcome.CONFIRMED) {
                    deleted++
                    ContentCache.invalidatePlaylist(id)
                } else {
                    failed++
                    if (problem == null) problem = result?.problem ?: PlaylistProblem.NETWORK
                }
                _batch.update { it.copy(done = index + 1, deleted = deleted, failed = failed, problem = problem) }
            }
            // 全部确认删除时只给轻提示；有未完成项才保留汇总对话框，并带上第一个失败原因。
            if (failed == 0 && deleted == ids.size) {
                _batch.value = PlaylistBatch()
                notify(PlaylistNoticeKind.DELETED, deleted, playlistId = ids.singleOrNull())
            } else {
                _batch.update { it.copy(running = false) }
            }
            _revision.update { it + 1 }
        }
    }

    fun dismissBatch() {
        if (_batch.value.running) return
        _batch.value = PlaylistBatch()
    }

    fun backToEditor() {
        if (_state.value.busy || _state.value.result?.outcome != PlaylistOutcome.FAILED) return
        pendingCommand = null
        _state.update { it.copy(result = null, problem = null) }
    }
    fun submit(targetId: Long = 0) {
        val state = _state.value
        if (!_enabled.value || state.busy || state.loading || state.result != null || state.ownerId <= 0) return
        val request = state.request ?: return
        val action = when (request.mode) {
            PlaylistPanelMode.ADD -> PlaylistAction.ADD
            PlaylistPanelMode.CREATE -> PlaylistAction.CREATE
            PlaylistPanelMode.EDIT -> PlaylistAction.NAME
            PlaylistPanelMode.REMOVE -> PlaylistAction.REMOVE
            PlaylistPanelMode.DELETE -> PlaylistAction.DELETE
            PlaylistPanelMode.REORDER -> PlaylistAction.REORDER
            PlaylistPanelMode.SUBSCRIBE -> PlaylistAction.SUBSCRIBE
            PlaylistPanelMode.UNSUBSCRIBE -> PlaylistAction.UNSUBSCRIBE
        }
        // 排序带的是整个歌单的新顺序：和其他动作一样走 request.songIds，但不带 draft。
        val command = PlaylistCommand(action, if (action == PlaylistAction.ADD) targetId else request.playlistId,
            if (action == PlaylistAction.CREATE || action == PlaylistAction.NAME) state.draft else null, request.songIds)
        run(command, verifyOnly = false)
    }
    fun retryRead() { pendingCommand?.let { run(it, verifyOnly = true) } }
    private fun run(command: PlaylistCommand, verifyOnly: Boolean) {
        if (_state.value.busy) return
        accountChanged()
        if (_state.value.request == null) return
        val token = generation
        val credential = cookie
        val owner = _state.value.ownerId
        _state.update { it.copy(busy = true, problem = null, result = null) }
        pendingCommand = command
        scope.launch {
            val result = try {
                if (!verifyOnly) persist(owner, command)
                val repository = PlaylistRepository(NetworkPlaylistGateway(credential),
                    accountCurrent = { current(token, credential) },
                    backupSnapshot = { snapshot ->
                        val file = backup(owner, snapshot)
                        publish(token, credential) { it.copy(backupFile = file) }
                    })
                if (verifyOnly) repository.verify(command) else repository.execute(command)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PlaylistResult(PlaylistOutcome.FAILED, command.playlistId.takeIf { it > 0 }, problem = PlaylistProblem.NETWORK) }
            if (!current(token, credential)) return@launch
            val nextCommand = command.copy(playlistId = result.playlistId ?: command.playlistId)
            pendingCommand = nextCommand
            if (result.outcome == PlaylistOutcome.PENDING || result.outcome == PlaylistOutcome.PARTIAL) runCatching { persist(owner, nextCommand) }
            else clearPending(owner)
            result.playlistId?.let { ContentCache.invalidatePlaylist(it) }
            _revision.update { it + 1 }
            if (result.outcome == PlaylistOutcome.CONFIRMED) {
                // 已确认就不再留一页「已确认完成」等用户点：收起面板，用轻提示报告结果。
                val finished = _state.value
                val name = (result.snapshot?.info?.name ?: command.draft?.name ?: finished.targetName).orEmpty()
                notify(noticeKindFor(command.action), command.songIds.size, name, result.playlistId)
                if (command.action == PlaylistAction.SUBSCRIBE || command.action == PlaylistAction.UNSUBSCRIBE)
                    applySubscription(command.action == PlaylistAction.SUBSCRIBE, command.playlistId, finished.snapshot, credential)
                pendingCommand = null
                publish(token, credential) { PlaylistPanelState() }
            } else {
                publish(token, credential) { it.copy(busy = false, result = result, snapshot = result.snapshot ?: it.snapshot,
                    visible = true, background = false) }
            }
        }
    }
    private fun noticeKindFor(action: PlaylistAction) = when (action) {
        PlaylistAction.ADD -> PlaylistNoticeKind.ADDED
        PlaylistAction.REMOVE -> PlaylistNoticeKind.REMOVED
        PlaylistAction.REORDER -> PlaylistNoticeKind.REORDERED
        PlaylistAction.CREATE -> PlaylistNoticeKind.CREATED
        PlaylistAction.DELETE -> PlaylistNoticeKind.DELETED
        PlaylistAction.SUBSCRIBE -> PlaylistNoticeKind.SUBSCRIBED
        PlaylistAction.UNSUBSCRIBE -> PlaylistNoticeKind.UNSUBSCRIBED
        else -> PlaylistNoticeKind.SAVED
    }
    /** 库页读到列表后发布；凭据已变（切号）的结果直接丢弃。 */
    fun publishLibrary(ownerId: Long, collection: PlaylistCollection, credential: String) {
        if (ownerId > 0 && credential.isNotEmpty() && credential == RetrofitClient.getCookie().orEmpty())
            _library.value = PlaylistLibrary(ownerId, collection)
    }

    /** 首页 / 搜索 / 详情需要收藏状态而库页没读过时补读一次；失败保持「未知」。 */
    fun refreshLibrary() {
        if (!_enabled.value) return
        val credential = RetrofitClient.getCookie().orEmpty()
        if (credential.isEmpty()) return
        scope.launch {
            try {
                val gateway = NetworkPlaylistGateway(credential)
                val uid = gateway.userId()
                if (uid > 0) publishLibrary(uid, gateway.collection(uid), credential)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
    }

    /** true 已收藏 / false 未收藏 / null 列表未读到或不完整，界面不据此给出确定状态。 */
    fun subscriptionOf(library: PlaylistLibrary?, id: Long): Boolean? {
        val collection = library?.collection ?: return null
        val item = collection.items.firstOrNull { it.id == id }
        return when {
            item != null -> !item.isOwnedBy(library.ownerId)
            collection.complete -> false
            else -> null
        }
    }

    /** 读回确认后先就地更新列表，让各页按钮立刻翻转，再补读一次服务端列表作准。 */
    private fun applySubscription(subscribed: Boolean, id: Long, snapshot: PlaylistSnapshot?, credential: String) {
        _library.update { library ->
            library ?: return@update null
            val rest = library.collection.items.filterNot { it.id == id }
            val items = if (subscribed && snapshot != null) rest + snapshot.info.copy(subscribed = true) else rest
            if (credential == RetrofitClient.getCookie().orEmpty()) library.copy(collection = library.collection.copy(items = items)) else library
        }
        refreshLibrary()
    }

    private fun current(token: Long, credential: String) = token == generation && credential == RetrofitClient.getCookie().orEmpty()
    private fun publish(token: Long, credential: String, transform: (PlaylistPanelState) -> PlaylistPanelState) {
        _state.update { if (current(token, credential)) transform(it) else it }
    }
    private fun requestFor(command: PlaylistCommand) = PlaylistPanelRequest(when (command.action) {
        PlaylistAction.CREATE -> PlaylistPanelMode.CREATE
        PlaylistAction.ADD -> PlaylistPanelMode.ADD
        PlaylistAction.REMOVE -> PlaylistPanelMode.REMOVE
        PlaylistAction.DELETE -> PlaylistPanelMode.DELETE
        PlaylistAction.REORDER -> PlaylistPanelMode.REORDER
        PlaylistAction.SUBSCRIBE -> PlaylistPanelMode.SUBSCRIBE
        PlaylistAction.UNSUBSCRIBE -> PlaylistPanelMode.UNSUBSCRIBE
        else -> PlaylistPanelMode.EDIT
    }, command.songIds, command.playlistId)

    private fun persist(uid: Long, command: PlaylistCommand) {
        if (uid <= 0) return
        val value = JSONObject().put("action", command.action.name).put("id", command.playlistId)
            .put("songs", JSONArray(command.songIds)).put("name", command.draft?.name)
            .put("description", command.draft?.description).put("privacy", command.draft?.privacy)
        if (!prefs().edit().putString("playlist_pending_$uid", value.toString()).commit()) throw java.io.IOException("pending record not saved")
    }
    private fun restore(uid: Long): PlaylistCommand? = runCatching {
        val value = JSONObject(prefs().getString("playlist_pending_$uid", null) ?: return null)
        val songs = value.optJSONArray("songs") ?: JSONArray()
        PlaylistCommand(PlaylistAction.valueOf(value.getString("action")), value.optLong("id"),
            if (value.isNull("name")) null else PlaylistDraft(value.getString("name"), value.optString("description"), value.optInt("privacy", -1)),
            (0 until songs.length()).map { songs.getLong(it) })
    }.getOrNull()
    private fun clearPending(uid: Long) { if (uid > 0 && app != null) prefs().edit().remove("playlist_pending_$uid").apply() }
    private fun backup(uid: Long, snapshot: PlaylistSnapshot): File {
        val directory = File(app!!.filesDir, "playlist-backups").apply { mkdirs() }
        val file = File(directory, "${snapshot.info.id}-${System.currentTimeMillis()}.json")
        val json = JSONObject().put("version", 1).put("owner", uid).put("id", snapshot.info.id)
            .put("name", snapshot.info.name).put("description", snapshot.description).put("privacy", snapshot.info.privacy)
            .put("tracks", JSONArray(snapshot.trackIds))
        FileOutputStream(file).use { it.write(json.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        val restored = JSONObject(file.readText())
        check(restored.toString() == json.toString())
        return file
    }
}
