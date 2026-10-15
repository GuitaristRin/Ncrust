package com.takahashirinta.ncrust.ui.i18n

data class Strings(
    // Navigation tabs
    val tabHome: String,
    val tabLibrary: String,
    val tabSearch: String,
    val tabUser: String,

    // Common
    val cancel: String,
    val close: String,
    val retry: String,
    val loading: String,
    val back: String,

    // Auth / Account
    val accountDialogTitle: String,
    val nicknameLabel: (String) -> String,
    val uidLabel: (String) -> String,
    val logoutButton: String,
    val notLoggedIn: String,
    val loginHint: String,

    // User screen — sections
    val qualitySectionTitle: String,
    val wifiQualityLabel: String,
    val mobileQualityLabel: String,
    val qualityOptions: List<String>,
    val playbackSectionTitle: String,
    val gaplessSectionTitle: String,
    val gaplessDescription: String,
    val lyricsTranslationLabel: String,
    val themeSectionTitle: String,
    val themeModeSectionTitle: String,
    val themeModeSystem: String,
    val themeModeDark: String,
    val themeModeLight: String,
    val themeColorNames: List<String>,
    val languageSectionTitle: String,
    val aboutButton: String,
    val storageSectionTitle: String,
    val cacheSizeLabel: (Long) -> String,
    val clearCache: String,
    val clearCacheConfirm: String,
    val cacheCleared: String,

    // Home screen
    val dailySongsTitle: String,
    val recommendPlaylistTitle: String,
    val newSongsTitle: String,
    val refreshLabel: String,
    val noMoreContent: String,
    val trackCountSongs: (Int) -> String,
    // 主页推荐歌单里首位的私人 FM 电台卡（标题用用户昵称, 不是 uid）
    val fmRadioTitle: (String) -> String,
    // 拿不到用户资料时电台卡仍常驻的通用标题
    val fmRadioTitleGeneric: String,
    val fmRadioSubtitle: String,

    // Library screen
    val categoryTracks: String,
    val categoryAlbums: String,
    val categoryPlaylists: String,
    val noSavedSongs: String,
    val noSavedAlbums: String,
    val noPlaylists: String,
    val notLoggedInForPlaylists: String,
    val loadFailed: (String?) -> String,
    val trackCount: (Int) -> String,
    val albumArtistAndCount: (String, Int) -> String,

    // Search screen
    val searchCategoryTracks: String,
    val searchCategoryAlbums: String,
    val searchCategoryArtists: String,
    val searchPlaceholder: String,
    val searchSongsEmpty: String,
    val searchAlbumsEmpty: String,
    val searchArtistsEmpty: String,

    // Player controls
    val prevButton: String,
    val playButton: String,
    val pauseButton: String,
    val nextButton: String,
    val lyricsButton: String,
    val queueButton: String,
    val addToLibraryButton: String,

    // Song / queue actions
    val actionAddToLibrary: String,
    val actionInsertNext: String,
    val actionAppendToQueue: String,
    val actionGoToArtist: String,
    val actionGoToAlbum: String,

    // Background activity permission (battery optimization whitelist)
    val batteryTitle: String,
    val batteryMessage: String,
    val batteryAllow: String,
    val batteryLater: String,

    // Native QR login (tablet / large screen)
    val qrLoginTitle: String,
    val qrScanHint: String,
    val qrScannedHint: String,
    val qrExpiredHint: String,
    val qrLoadFailed: String,
    // 803 拿到 cookie 后用 account/get 校验的结果: 无效 / 网络失败(判不了) 各自提示
    val qrSessionInvalid: String,
    val qrNetworkError: String,
    val qrGenericLogin: String,

    // Phone-side QR scan to authorize another device (LAN cookie handoff)
    val scanEntryTitle: String,
    val scanPrompt: String,
    val scanPermissionNeeded: String,
    val scanNoCookie: String,
    val scanSuccess: String,
    val scanFailed: String,
    val scanConnecting: String,

    val clearQueue: String,
    val actionAddToPlaylist: String,
    val actionRemoveFromLibrary: String,
    val actionSaveAlbum: String,
    // 专辑已收藏时的按钮文案(取消收藏)
    val actionUnsaveAlbum: String,
    val unknownArtist: String,
    val playAllButton: String,

    // Play-all dialog
    val songCountFormat: (Int) -> String,
    val playNowTitle: String,
    val playNowDesc: String,
    val insertNextTitle: String,
    val insertNextDesc: String,
    val appendQueueTitle: String,
    val appendQueueDesc: String,

    // Library「单曲」整列播放：全量详情是按红心 id 表分批补的，期间要有加载反馈，
    // 部分批次失败时按实际拿到的曲目执行并如实提示。
    val loadingSongsCount: (Int) -> String,
    val playAllPartial: (Int) -> String,

    // About
    val aboutTitle: String,
    val aboutAppSubtitle: String,
    val aboutSectionProject: String,
    val aboutVersion: String,
    val aboutDeveloper: String,
    val aboutLicense: String,
    val aboutRepository: String,
    val aboutSectionTechStack: String,
    val aboutLangLabel: String,
    val aboutUIFrameworkLabel: String,
    val aboutDesignSystemLabel: String = "Design System",
    val aboutAudioEngineLabel: String,
    val aboutNetworkLabel: String,
    val aboutImageLabel: String,
    val aboutSectionTeam: String,
    val aboutRoleDev: String,
    val aboutRoleTester: String,
    val aboutSectionCredits: String,
    val aboutCreditCli: String,
    val aboutCreditAnim: String,
    val aboutCreditDesign: String,

    // Player UI
    val noLyrics: String,
    val emptyQueue: String,
    val collapsePlayer: String,

    // Detail page titles and content
    val artistDetailTitle: String,
    val albumDetailTitle: String,
    val playlistDetailTitle: String,
    val unknownArtistName: String,
    val noAlbums: String,
    val noHotSongs: String,
    val artistAlbumCount: (Int) -> String,
    val artistSongCount: (Int) -> String,
    val albumReleaseDate: (String) -> String,
    val albumLabel: (String) -> String,
    val artistDataLoadFailed: (Int) -> String,
    val artistStats: (Int, Int) -> String,

    // Accessibility content descriptions
    val coverDesc: String,
    val albumCoverDesc: String,
    val artistAvatarDesc: String,
    val playlistCoverDesc: String,
    val userAvatarDesc: String,

    // Search
    val clearSearchButton: String,

    // Song detail screen
    val songDetailTitle: String,
    val unknownAlbum: String,
    val lyricsLabel: String,

    // Player queue panel
    val queueTitle: String,
    val playModeButton: String,
    val saveAsPlaylist: String,
    val noSongPlaying: String,
    val queueSectionPast: String,
    val queueSectionNow: String,
    val queueSectionUpcoming: String,
    val queueInfinityPlaceholder: String,
    val queueClearAll: String,

    // User screen
    val userIconDesc: String,

    // Search history
    val searchHistoryClear: String,
    val searchHistoryEmpty: String,
    val searchHistoryDelete: String,

    // Feedback toasts
    val addedToLibrary: String,
    val removedFromLibrary: String,

    // Cloud sync state (local-only optimistic likes awaiting server confirmation)
    val pendingSyncCount: (Int) -> String,
    val retrySync: String,

    // Sharing
    val actionShare: String,
    val shareChooserTitle: String,

    // ---------- 账号权益：会话失效 / VIP / 播放限制（spec/api/entitlement.md） ----------
    // 全局失效横幅（首页 / 收藏页顶部，点击走登录流程）
    val sessionExpiredBanner: String,
    // 首页每日推荐在非 Valid 会话下的一行说明（不把通用列表冒充成个性化推荐）
    val dailySongsLoginHint: String,
    // VIP 会员名（按 SVIP > 黑胶 VIP > 音乐包 的优先级取）
    val vipKindSvip: String,
    val vipKindVip: String,
    val vipKindPackage: String,
    // 有效：(会员名, 到期日期)
    val vipActiveLabel: (String, String) -> String,
    // 已过期：(会员名, 到期日期)
    val vipLapsedLabel: (String, String) -> String,
    val vipNoneLabel: String,
    // 接口失败（含 301）：显示为未知，绝不显示「未开通」
    val vipUnknownLabel: String,
    // 音质徽标位置的「试听」
    val trialBadge: String,
    // 试听提示：(秒数, 原因)
    val trialToast: (Int, String) -> String,
    // 判定原因文案
    val playbackReasonVip: String,
    val playbackReasonAlbum: String,
    val playbackReasonCopyright: String,
    val playbackReasonLogin: String,
    // 试听时的登录原因与跳过时不同：登录后仍可能只给到试听
    val playbackReasonLoginTrial: String,
    val playbackReasonUnknown: String,
    // 跳过单曲：(歌名, 原因)
    val skippedSong: (String, String) -> String,
    // 连续多首不可播合并成一条：(首数)
    val skippedSongs: (Int) -> String,
    // 音质被封顶：(实际档位名)
    val qualityCapped: (String) -> String,
    val qualityCappedLogin: String,
    // 设置项「非会员遇到 VIP 歌曲」
    val trialSettingTitle: String,
    val trialSettingPlay: String,
    val trialSettingSkip: String,
)

/** 字节数格式化为人类可读的 B/KB/MB/GB，供 cacheSizeLabel 复用。 */
fun formatCacheBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
