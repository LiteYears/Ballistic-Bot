package com.olympquant.terminal

import android.app.Application
import com.olympquant.terminal.data.OlympQuantDatabase
import com.olympquant.terminal.data.OlympQuantRepository
import com.olympquant.terminal.network.OlympTradeWsClient

class OlympQuantApp : Application() {
    val database by lazy { OlympQuantDatabase.getInstance(this) }
    val repository by lazy { OlympQuantRepository(this, database.dao()) }
    val wsClient by lazy { OlympTradeWsClient() }
}
