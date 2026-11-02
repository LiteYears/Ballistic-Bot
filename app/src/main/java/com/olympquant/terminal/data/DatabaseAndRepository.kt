package com.olympquant.terminal.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import com.olympquant.terminal.BuildConfig
import com.olympquant.terminal.domain.HardLockedMandate
import com.olympquant.terminal.domain.RiskConfig
import com.olympquant.terminal.domain.StrategyConfig
import com.olympquant.terminal.domain.TerminalDensityMode
import com.olympquant.terminal.domain.TradeSettingsConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

private val Context.dataStore by preferencesDataStore(name = "olympquant_prefs")

@Entity(tableName = "user_profiles")
data class UserProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val username: String,
    val email: String,
    val passwordHash: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "strategies")
data class StrategyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val isPreset: Boolean,
    val isEnabled: Boolean,
    val durationSeconds: Int,
    val stakeAmount: Double,
    val cooldownMs: Long,
    val minConfidence: Double,
    val triggerType: String,
    val triggerThreshold: Double,
    val regimeAdaptiveMode: String,
    val filtersJson: String
)

@Immutable
@Entity(tableName = "trade_journal")
data class TradeJournalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val brokerTradeId: Long,
    val timestampMs: Long,
    val strategyName: String,
    val pair: String,
    val direction: String,
    val stake: Double,
    val durationSeconds: Int,
    val accountGroup: String,
    val accountId: Long,
    val wsLatencyMs: Long,
    val hmmState: Int,
    val hmmRegime: String,
    val hmmConfidence: Double,
    val hvoiScore: Double,
    val volatility: Double,
    val velocity: Double,
    val consistency: Double,
    val slopeRatio: Double,
    val srDistance: Double,
    val trendAgeSeconds: Double,
    val entryPrice: Double,
    val exitPrice: Double,
    val outcome: String, // PENDING, WIN, LOSS, TIE
    val profitLoss: Double
)

@Dao
interface OlympQuantDao {
    @Query("SELECT * FROM user_profiles WHERE LOWER(email) = LOWER(:email) LIMIT 1")
    suspend fun getUserByEmail(email: String): UserProfileEntity?

    @Query("SELECT COUNT(*) FROM user_profiles")
    suspend fun getUserCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserProfileEntity): Long

    @Query("DELETE FROM strategies")
    suspend fun clearAllStrategies()

    @Query("SELECT * FROM trade_journal ORDER BY timestampMs DESC")
    fun observeTradeJournal(): Flow<List<TradeJournalEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrade(trade: TradeJournalEntity): Long

    @Query("SELECT * FROM trade_journal WHERE brokerTradeId = :brokerTradeId OR (outcome = 'PENDING' AND pair = :pair) ORDER BY timestampMs DESC LIMIT 1")
    suspend fun findPendingTrade(brokerTradeId: Long, pair: String): TradeJournalEntity?

    @Update
    suspend fun updateTrade(trade: TradeJournalEntity)

    @Query("DELETE FROM trade_journal")
    suspend fun clearTradeJournal()
}

@Database(
    entities = [UserProfileEntity::class, StrategyEntity::class, TradeJournalEntity::class],
    version = 3,
    exportSchema = false
)
abstract class OlympQuantDatabase : RoomDatabase() {
    abstract fun dao(): OlympQuantDao

    companion object {
        @Volatile
        private var INSTANCE: OlympQuantDatabase? = null

        fun getInstance(context: Context): OlympQuantDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OlympQuantDatabase::class.java,
                    "olympquant_terminal.db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build().also { INSTANCE = it }
            }
        }
    }
}

@Immutable
data class SessionPreferences(
    val loggedInEmail: String = "",
    val loggedInUsername: String = "",
    val accessToken: String = "",
    val wsUri: String = DEFAULT_WS_URI,
    val selectedPair: String = "LATAM_X",
    val accountGroup: String = "demo",
    val demoAccountId: Long = 0L,
    val realAccountId: Long = 0L,
    val demoBalance: Double = 10000.0,
    val realBalance: Double = 0.0,
    val densityMode: TerminalDensityMode = TerminalDensityMode.PRO_TERMINAL,
    val autoReconnectSession: Boolean = true,
    val tokenCapturedAtMs: Long = 0L,
    val lastSessionMode: String = "SYNTHETIC_FEED",
    val dailyPnlResetAtMs: Long = 0L,
    val riskConfig: RiskConfig = RiskConfig(),
    val tradeSettings: TradeSettingsConfig = TradeSettingsConfig()
) {
    companion object {
        const val DEFAULT_WS_URI =
            "wss://ws.olymptrade.com/otp?cid_ver=1&cid_app=web%40OlympTrade%402025.3.26878%4026878&cid_device=%40%40desktop&cid_os=windows%4010"
    }
}

class OlympQuantRepository(
    private val context: Context,
    private val dao: OlympQuantDao
) {
    private object Keys {
        val LOGGED_IN_EMAIL = stringPreferencesKey("logged_in_email")
        val LOGGED_IN_USERNAME = stringPreferencesKey("logged_in_username")
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val WS_URI = stringPreferencesKey("ws_uri")
        val SELECTED_PAIR = stringPreferencesKey("selected_pair")
        val ACCOUNT_GROUP = stringPreferencesKey("account_group")
        val DEMO_ACCOUNT_ID = longPreferencesKey("demo_account_id")
        val REAL_ACCOUNT_ID = longPreferencesKey("real_account_id")
        val DEMO_BALANCE = doublePreferencesKey("demo_balance")
        val REAL_BALANCE = doublePreferencesKey("real_balance")
        val DENSITY_MODE = stringPreferencesKey("density_mode")
        val AUTO_RECONNECT_SESSION = booleanPreferencesKey("auto_reconnect_session")
        val TOKEN_CAPTURED_AT_MS = longPreferencesKey("token_captured_at_ms")
        val LAST_SESSION_MODE = stringPreferencesKey("last_session_mode")
        val DAILY_PNL_RESET_AT_MS = longPreferencesKey("daily_pnl_reset_at_ms")
    }

    val sessionFlow: Flow<SessionPreferences> = context.dataStore.data.map { prefs ->
        val defaultEnvToken = runCatching {
            BuildConfig.OLYMP_DEFAULT_ACCESS_TOKEN.takeIf { it != "YOUR_OLYMP_ACCESS_TOKEN_HERE" } ?: ""
        }.getOrDefault("")
        val savedToken = prefs[Keys.ACCESS_TOKEN] ?: ""
        SessionPreferences(
            loggedInEmail = prefs[Keys.LOGGED_IN_EMAIL] ?: "",
            loggedInUsername = prefs[Keys.LOGGED_IN_USERNAME] ?: "",
            accessToken = savedToken.ifBlank { defaultEnvToken },
            wsUri = prefs[Keys.WS_URI] ?: SessionPreferences.DEFAULT_WS_URI,
            selectedPair = prefs[Keys.SELECTED_PAIR] ?: "LATAM_X",
            accountGroup = prefs[Keys.ACCOUNT_GROUP] ?: "demo",
            demoAccountId = prefs[Keys.DEMO_ACCOUNT_ID] ?: 0L,
            realAccountId = prefs[Keys.REAL_ACCOUNT_ID] ?: 0L,
            demoBalance = prefs[Keys.DEMO_BALANCE] ?: 10000.0,
            realBalance = prefs[Keys.REAL_BALANCE] ?: 0.0,
            densityMode = runCatching {
                TerminalDensityMode.valueOf(prefs[Keys.DENSITY_MODE] ?: TerminalDensityMode.PRO_TERMINAL.name)
            }.getOrDefault(TerminalDensityMode.PRO_TERMINAL),
            autoReconnectSession = prefs[Keys.AUTO_RECONNECT_SESSION] ?: true,
            tokenCapturedAtMs = prefs[Keys.TOKEN_CAPTURED_AT_MS] ?: 0L,
            lastSessionMode = prefs[Keys.LAST_SESSION_MODE] ?: "SYNTHETIC_FEED",
            dailyPnlResetAtMs = prefs[Keys.DAILY_PNL_RESET_AT_MS] ?: 0L,
            riskConfig = RiskConfig(),
            tradeSettings = TradeSettingsConfig()
        )
    }

    // Hard-locked single strategy flow — immutable by design
    val strategiesFlow: Flow<List<StrategyConfig>> = flowOf(listOf(HardLockedMandate.lockedStrategyConfig()))

    val tradeJournalFlow: Flow<List<TradeJournalEntity>> = dao.observeTradeJournal()

    suspend fun ensureSeeded() {
        runCatching { dao.clearAllStrategies() }
    }

    suspend fun registerUser(username: String, email: String, passwordPlain: String): Result<UserProfileEntity> {
        val existing = dao.getUserByEmail(email.trim())
        if (existing != null) {
            return Result.failure(IllegalArgumentException("An account with this email already exists."))
        }
        val entity = UserProfileEntity(
            username = username.trim(),
            email = email.trim(),
            passwordHash = hashPassword(passwordPlain)
        )
        val id = dao.insertUser(entity)
        val saved = entity.copy(id = id)
        context.dataStore.edit { prefs ->
            prefs[Keys.LOGGED_IN_EMAIL] = saved.email
            prefs[Keys.LOGGED_IN_USERNAME] = saved.username
        }
        return Result.success(saved)
    }

    suspend fun loginUser(email: String, passwordPlain: String): Result<UserProfileEntity> {
        val user = dao.getUserByEmail(email.trim())
            ?: return Result.failure(IllegalArgumentException("No workstation profile found for $email."))
        if (user.passwordHash != hashPassword(passwordPlain)) {
            return Result.failure(IllegalArgumentException("Invalid password for workstation profile."))
        }
        context.dataStore.edit { prefs ->
            prefs[Keys.LOGGED_IN_EMAIL] = user.email
            prefs[Keys.LOGGED_IN_USERNAME] = user.username
        }
        return Result.success(user)
    }

    suspend fun logoutUser() {
        context.dataStore.edit { prefs ->
            prefs[Keys.LOGGED_IN_EMAIL] = ""
            prefs[Keys.LOGGED_IN_USERNAME] = ""
        }
    }

    suspend fun updateBrokerCredentials(accessToken: String, wsUri: String) {
        val cleanedToken = accessToken.trim()
        context.dataStore.edit { prefs ->
            prefs[Keys.ACCESS_TOKEN] = cleanedToken
            if (wsUri.isNotBlank()) {
                prefs[Keys.WS_URI] = wsUri.trim()
            }
            if (cleanedToken.isNotBlank()) {
                prefs[Keys.TOKEN_CAPTURED_AT_MS] = System.currentTimeMillis()
                prefs[Keys.LAST_SESSION_MODE] = "AUTHENTICATED"
            }
        }
    }

    suspend fun updatePersistentSessionMeta(
        autoReconnect: Boolean? = null,
        lastMode: String? = null,
        tokenCapturedAtMs: Long? = null
    ) {
        context.dataStore.edit { prefs ->
            autoReconnect?.let { prefs[Keys.AUTO_RECONNECT_SESSION] = it }
            lastMode?.let { prefs[Keys.LAST_SESSION_MODE] = it }
            tokenCapturedAtMs?.let { prefs[Keys.TOKEN_CAPTURED_AT_MS] = it }
        }
    }

    suspend fun updateSelectedPair(pair: String) {
        context.dataStore.edit { it[Keys.SELECTED_PAIR] = pair }
    }

    suspend fun updateAccountGroup(group: String) {
        context.dataStore.edit { it[Keys.ACCOUNT_GROUP] = group }
    }

    suspend fun updateDensityMode(mode: TerminalDensityMode) {
        context.dataStore.edit { it[Keys.DENSITY_MODE] = mode.name }
    }

    suspend fun updateAccountBalances(
        demoAccountId: Long?,
        demoBalance: Double?,
        realAccountId: Long?,
        realBalance: Double?
    ) {
        context.dataStore.edit { prefs ->
            demoAccountId?.let { if (it > 0) prefs[Keys.DEMO_ACCOUNT_ID] = it }
            demoBalance?.let { prefs[Keys.DEMO_BALANCE] = it }
            realAccountId?.let { if (it > 0) prefs[Keys.REAL_ACCOUNT_ID] = it }
            realBalance?.let { prefs[Keys.REAL_BALANCE] = it }
        }
    }

    suspend fun recordTradeEntry(trade: TradeJournalEntity): Long {
        return dao.insertTrade(trade)
    }

    suspend fun settleTrade(brokerTradeId: Long, pair: String, exitPrice: Double, winStatus: String, profitLoss: Double) {
        val pending = dao.findPendingTrade(brokerTradeId, pair) ?: return
        dao.updateTrade(
            pending.copy(
                brokerTradeId = if (brokerTradeId > 0) brokerTradeId else pending.brokerTradeId,
                exitPrice = exitPrice,
                outcome = winStatus,
                profitLoss = profitLoss
            )
        )
    }

    suspend fun clearJournal() {
        dao.clearTradeJournal()
    }

    private fun hashPassword(plain: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(plain.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun defaultPresetStrategies(): List<StrategyConfig> = listOf(
            HardLockedMandate.lockedStrategyConfig()
        )
    }
}
