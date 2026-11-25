package com.eric.todo.sync

import android.content.Context
import android.os.Build
import com.eric.todo.data.SyncMerge
import com.eric.todo.data.SyncState
import com.eric.todo.data.TaskStore
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Live sync between phone and tablet through Firebase (Google's online database).
 * The whole list lives in one document: users/{your account}/todo/state.
 * Every change on a device is sent within a second; changes from the other device
 * arrive by themselves. Each device keeps its own copy, so it works with no signal.
 */
object CloudSync {

    sealed interface Status {
        data object NotConfigured : Status                 // Firebase file not added to the app yet
        data object SignedOut : Status
        data object Syncing : Status
        data class Synced(val at: Long) : Status
        data class Offline(val message: String) : Status
        data class Error(val message: String) : Status
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private lateinit var appContext: Context
    private var ready = false
    private var listener: ListenerRegistration? = null
    private var pushJob: Job? = null
    @Volatile private var needsPush = false

    private val _status = MutableStateFlow<Status>(Status.NotConfigured)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _email = MutableStateFlow<String?>(null)
    val email: StateFlow<String?> = _email.asStateFlow()

    private val auth get() = FirebaseAuth.getInstance()
    // The database may be named "(default)" or "default" depending on how it was created in Firebase.
    @Volatile private var dbId = "(default)"
    private val db: FirebaseFirestore
        get() = if (dbId == "(default)") FirebaseFirestore.getInstance()
                else FirebaseFirestore.getInstance(FirebaseApp.getInstance(), dbId)

    private fun isMissingDatabase(e: Throwable?): Boolean {
        var t = e
        while (t != null) {
            val code = (t as? com.google.firebase.firestore.FirebaseFirestoreException)?.code
            val m = t.message.orEmpty()
            if (code == com.google.firebase.firestore.FirebaseFirestoreException.Code.NOT_FOUND && m.contains("database", true)) return true
            if (m.contains("database", true) && (m.contains("does not exist", true) || m.contains("not found", true))) return true
            t = t.cause
        }
        return false
    }

    /** Switches to the other database name. Returns false if both were tried. */
    private fun tryOtherDatabase(): Boolean {
        if (dbId != "(default)") return false
        dbId = "default"
        appContext.getSharedPreferences("cloud", Context.MODE_PRIVATE).edit().putString("db_id", dbId).apply()
        return true
    }

    /** Call once when the app starts. */
    @Synchronized
    fun init(context: Context) {
        if (ready) return
        appContext = context.applicationContext
        val app = runCatching { FirebaseApp.getApps(appContext).firstOrNull() ?: FirebaseApp.initializeApp(appContext) }.getOrNull()
        if (app == null) {
            _status.value = Status.NotConfigured
            return
        }
        ready = true
        dbId = appContext.getSharedPreferences("cloud", Context.MODE_PRIVATE).getString("db_id", "(default)") ?: "(default)"
        TaskStore.onLocalChange = { schedulePush() }
        auth.addAuthStateListener { a ->
            val user = a.currentUser
            _email.value = user?.email
            if (user == null) {
                stopListening()
                _status.value = Status.SignedOut
            } else {
                startListening(user.uid)
            }
        }
    }

    val isConfigured: Boolean get() = ready
    val isSignedIn: Boolean get() = ready && auth.currentUser != null

    // ---- Account ---------------------------------------------------------

    suspend fun signIn(email: String, password: String): String? = account {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
    }

    suspend fun createAccount(email: String, password: String): String? = account {
        auth.createUserWithEmailAndPassword(email.trim(), password).await()
    }

    suspend fun resetPassword(email: String): String? = account {
        auth.sendPasswordResetEmail(email.trim()).await()
    }

    fun signOut() {
        if (!ready) return
        auth.signOut()
    }

    /** Runs an account action; returns null when it worked, or a message saying what went wrong. */
    private suspend fun account(block: suspend () -> Unit): String? {
        if (!ready) return "Sync isn't connected to Firebase yet."
        return try {
            block(); null
        } catch (e: FirebaseAuthWeakPasswordException) {
            "Password must be at least 6 characters."
        } catch (e: FirebaseAuthUserCollisionException) {
            "That email already has an account. Tap Sign in instead."
        } catch (e: FirebaseAuthInvalidUserException) {
            "No account with that email. Tap Create account on your first device."
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            "Wrong email or password."
        } catch (e: FirebaseNetworkException) {
            "No internet connection."
        } catch (e: Exception) {
            e.message ?: "Something went wrong."
        }
    }

    // ---- Syncing ---------------------------------------------------------

    private fun doc(uid: String): DocumentReference =
        db.collection("users").document(uid).collection("todo").document("state")

    private fun startListening(uid: String) {
        stopListening()
        _status.value = Status.Syncing
        needsPush = true   // first time on this device: send what it has
        listener = doc(uid).addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
            if (err != null) {
                if (isMissingDatabase(err) && tryOtherDatabase()) {
                    startListening(uid)
                    return@addSnapshotListener
                }
                _status.value = Status.Error(friendly(err))
                return@addSnapshotListener
            }
            if (snap == null) return@addSnapshotListener
            val json = snap.getString("json")
            if (json != null && !snap.metadata.hasPendingWrites()) {
                runCatching {
                    val remote = TaskStore.parseState(json)
                    val local = TaskStore.syncState(appContext)
                    val merged = SyncMerge.merge(local, remote)
                    if (TaskStore.normalized(merged) != TaskStore.normalized(local)) TaskStore.applySync(appContext, merged)
                    if (TaskStore.normalized(merged) != TaskStore.normalized(remote)) needsPush = true
                }
            }
            if (!snap.metadata.isFromCache) {
                if (needsPush || json == null) schedulePush(0) else _status.value = Status.Synced(System.currentTimeMillis())
            } else if (_status.value is Status.Syncing) {
                _status.value = Status.Offline("Offline. Changes will sync when you're back online.")
            }
        }
    }

    private fun stopListening() {
        listener?.remove()
        listener = null
        pushJob?.cancel()
    }

    private val recentPushes = ArrayDeque<Long>()

    private fun schedulePush(delayMs: Long = 800) {
        if (!isSignedIn) return
        // Safety: never let two devices bounce changes back and forth forever.
        val now = System.currentTimeMillis()
        synchronized(recentPushes) {
            while (recentPushes.isNotEmpty() && now - recentPushes.first() > 20_000) recentPushes.removeFirst()
            if (recentPushes.size >= 10) {
                needsPush = false
                _status.value = Status.Synced(now)
                return
            }
            recentPushes.addLast(now)
        }
        needsPush = true
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(delayMs)
            push()
        }
    }

    /** Sync right now (the Sync button). Returns null if OK, or what went wrong. */
    suspend fun syncNow(): String? {
        if (!ready) return "Sync isn't connected to Firebase yet."
        if (auth.currentUser == null) return "Sign in first (Settings → Sync phone & tablet)."
        return push()
    }

    private suspend fun push(): String? = mutex.withLock {
        val uid = auth.currentUser?.uid ?: return@withLock "Not signed in."
        _status.value = Status.Syncing
        try {
            val ref = doc(uid)
            val merged = db.runTransaction { tx ->
                val snap = tx.get(ref)
                val remoteJson = snap.getString("json")
                val remote = remoteJson?.let { TaskStore.parseState(it) } ?: SyncState(emptyList(), emptyMap(), 0)
                val m = SyncMerge.merge(TaskStore.syncState(appContext), remote)
                val json = TaskStore.stateJson(m)
                if (remoteJson == null || TaskStore.normalized(m) != TaskStore.normalized(remote)) {
                    tx.set(ref, mapOf(
                        "json" to json,
                        "updated_at" to System.currentTimeMillis(),
                        "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    ))
                }
                m
            }.await()
            // Keep any edit made on this device while the sync was running.
            val current = TaskStore.syncState(appContext)
            val final = SyncMerge.merge(current, merged)
            if (TaskStore.normalized(final) != TaskStore.normalized(current)) TaskStore.applySync(appContext, final)
            needsPush = TaskStore.normalized(final) != TaskStore.normalized(merged)
            _status.value = Status.Synced(System.currentTimeMillis())
            if (needsPush) schedulePush()
            null
        } catch (e: Exception) {
            needsPush = true
            if (isMissingDatabase(e) && tryOtherDatabase()) {
                auth.currentUser?.uid?.let { startListening(it) }
                return@withLock null
            }
            val msg = friendly(e)
            _status.value = if (e is FirebaseNetworkException || msg.contains("offline", true) || msg.contains("unavailable", true))
                Status.Offline("Offline. Changes will sync when you're back online.")
            else Status.Error(msg)
            msg
        }
    }

    /** When the app comes back to the screen, catch up anything waiting. */
    fun onAppOpened() {
        if (isSignedIn && (needsPush || _status.value !is Status.Synced)) schedulePush(0)
    }

    private fun friendly(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("PERMISSION_DENIED", true) || m.contains("permission", true) ->
                "Firebase blocked the sync. Check the Firestore Rules were published."
            m.contains("NOT_FOUND", true) && m.contains("database", true) ->
                "The Firestore database hasn't been created yet in Firebase."
            m.contains("offline", true) || m.contains("UNAVAILABLE", true) -> "Offline"
            else -> m.ifBlank { "Sync failed" }
        }
    }
}
