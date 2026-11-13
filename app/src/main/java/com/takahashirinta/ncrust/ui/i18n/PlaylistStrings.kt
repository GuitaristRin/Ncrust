package com.takahashirinta.ncrust.ui.i18n

data class PlaylistStrings(
    val experimental: String, val experimentalDescription: String,
    val create: String, val edit: String, val add: String, val manage: String,
    val name: String, val description: String, val visibility: String,
    val public: String, val private: String, val unknown: String,
    val owned: String, val subscribed: String,
    val remove: String, val delete: String, val save: String, val done: String,
    val confirmed: String, val pending: String, val partial: (Int, Int) -> String,
    val failed: String, val verify: String, val backToEdit: String,
    val login: String, val permission: String, val rateLimit: String, val network: String,
    val accountChanged: String, val invalid: String, val incomplete: String, val unknownCreate: String,
    val deleteHint: (String) -> String, val removeHint: (Int) -> String,
    val backup: (String) -> String, val open: String, val stopTracking: String,
    val selectTracks: String, val selectAll: String, val selectionCount: (Int) -> String,
    val sortTracks: String, val saveOrder: String, val reorderHint: (Int) -> String,
    val moveUp: String, val moveDown: String, val removeShort: String,
    val editTracks: String, val editInfo: String, val orderChanged: String,
    val discardOrder: String, val discardOrderHint: String, val dragToReorder: String, val working: String,
    val noticeAdded: (String) -> String, val noticeRemoved: (Int) -> String, val noticeReordered: String,
    val noticeCreated: (String) -> String, val noticeSaved: String, val noticeDeleted: (Int) -> String, val view: String,
    val playlistSelectionCount: (Int) -> String,
    val batchDeleteHint: (Int) -> String, val batchProgress: (Int, Int) -> String,
    val batchSummary: (Int, Int) -> String
)
