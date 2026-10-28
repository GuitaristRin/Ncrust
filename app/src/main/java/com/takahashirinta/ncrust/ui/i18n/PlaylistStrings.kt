package com.takahashirinta.ncrust.ui.i18n

data class PlaylistStrings(
    val experimental: String, val experimentalDescription: String,
    val create: String, val edit: String, val add: String,
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
    val selectTracks: String, val selectAll: String, val selectionCount: (Int) -> String
)
