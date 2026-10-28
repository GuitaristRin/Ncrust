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

enum class PlaylistPanelMode { ADD, CREATE, EDIT, REMOVE, DELETE }
data class PlaylistPanelRequest(val mode: PlaylistPanelMode, val songIds: List<Long> = emptyList(), val playlistId: Long = 0)
data class PlaylistPanelState(
    val visible: Boolean = false, val request: PlaylistPanelRequest? = null,
    val loading: Boolean = false, val busy: Boolean = false, val ownerId: Long = 0,
    val collection: PlaylistCollection = PlaylistCollection(emptyList(), false),
    val snapshot: PlaylistSnapshot? = null,
    val draft: PlaylistDraft = PlaylistDraft("", "", 10),
    val problem: PlaylistProblem? = null, val result: PlaylistResult? = null,
    val backupFile: File? = null
)

/** 页面只观察任务；关闭面板或导航离开不会取消已经发出的写入。 */
object PlaylistManager {
    private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _enabled = MutableStateFlow(false)
    val enabled = _enabled.asStateFlow()
    private val _state = MutableStateFlow(PlaylistPanelState())
    val state = _state.asStateFlow()
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
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
        }
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
                    backupBeforeDelete = { snapshot ->
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
            publish(token, credential) { it.copy(busy = false, result = result, snapshot = result.snapshot ?: it.snapshot) }
        }
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
