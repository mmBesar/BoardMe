package com.mmbesar.boardme

import android.app.Application
import com.mmbesar.boardme.data.BoardDatabase
import com.mmbesar.boardme.data.BoardRepository
import com.mmbesar.boardme.security.CredentialStore
import com.mmbesar.boardme.ui.ThemePrefs

/**
 * Creates the long-lived objects once and shares them with the whole app.
 * (A tiny hand-written "dependency container" - no extra framework needed.)
 */
class BoardMeApplication : Application() {
    val database: BoardDatabase by lazy { BoardDatabase.create(this) }
    val credentialStore: CredentialStore by lazy { CredentialStore(this) }
    val repository: BoardRepository by lazy { BoardRepository(database.dao(), credentialStore) }
    val themePrefs: ThemePrefs by lazy { ThemePrefs(this) }
}
