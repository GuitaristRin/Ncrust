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
    val loginDialogTitle: String,
    val webLoginButton: String,
    val manualCookieHint: String,
    val cookieFieldLabel: String,
    val saveCookieButton: String,
    val accountDialogTitle: String,
    val nicknameLabel: (String) -> String,
    val uidLabel: (String) -> String,
    val updateCookieButton: String,
    val logoutButton: String,
    val notLoggedIn: String,
    val loginHint: String,

    // User screen — sections
    val qualitySectionTitle: String,
    val wifiQualityLabel: String,
    val mobileQualityLabel: String,
    val qualityOptions: List<String>,
    val gaplessSectionTitle: String,
    val gaplessDescription: String,
    val themeSectionTitle: String,
    val themeColorNames: List<String>,
    val languageSectionTitle: String,
    val aboutButton: String,

    // Home screen
    val dailySongsTitle: String,
    val recommendPlaylistTitle: String,
    val newSongsTitle: String,
    val noMoreContent: String,
    val trackCountSongs: (Int) -> String,

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
    val actionAddToPlaylist: String,
    val actionRemoveFromLibrary: String,
    val unknownArtist: String,
    val playAllButton: String,

    // Play-all dialog
    val songCountFormat: (Int) -> String,
    val playNowTitle: String,
    val playNowDesc: String,
    val insertNextTitle: String,
    val insertNextDesc: String,

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
)
