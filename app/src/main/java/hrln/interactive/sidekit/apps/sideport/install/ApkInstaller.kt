package hrln.interactive.sidekit.apps.sideport.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import hrln.interactive.sidekit.apps.sideport.registry.PortedApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

sealed interface InstallStatus {
    data object Idle : InstallStatus
    data class Working(val step: String) : InstallStatus
    /** Android is showing its install confirmation. */
    data class Confirming(val label: String) : InstallStatus
    data class Installed(val packageName: String, val label: String) : InstallStatus
    data class Failed(val message: String) : InstallStatus
}

/**
 * Installs APKs the user picks and adds them to Sideport.
 *
 * Takes a single `.apk` or a split bundle (`.apks`, `.xapk`, `.apkm`: a zip of APKs; any OBB data
 * in an XAPK is not copied). Uses a [PackageInstaller] session, so Android shows its own
 * confirmation; Sidekit needs "Install unknown apps" allowed once ([canInstall]).
 */
object ApkInstaller {
    const val ACTION_RESULT = "hrln.interactive.sidekit.sideport.INSTALL_RESULT"
    private const val EXTRA_LABEL = "label"
    private const val EXTRA_PACKAGE = "package"

    private val _status = MutableStateFlow<InstallStatus>(InstallStatus.Idle)
    val status: StateFlow<InstallStatus> = _status.asStateFlow()

    fun clear() { _status.value = InstallStatus.Idle }

    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    suspend fun install(context: Context, uri: Uri) {
        val app = context.applicationContext
        _status.value = InstallStatus.Working("Reading file…")
        val result = runCatching { withContext(Dispatchers.IO) { stageAndCommit(app, uri) } }
        result.exceptionOrNull()?.let { _status.value = InstallStatus.Failed(it.localizedMessage ?: it.javaClass.simpleName) }
    }

    private fun stageAndCommit(context: Context, uri: Uri) {
        val dir = File(context.cacheDir, "sideport-install").apply { deleteRecursively(); mkdirs() }
        val download = File(dir, "download")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Couldn't open the file" }
            download.outputStream().use { input.copyTo(it) }
        }
        val apks = splitApks(download, dir)
        require(apks.isNotEmpty()) { "That file has no APK in it" }

        val pm = context.packageManager
        val base = apks.firstNotNullOfOrNull { apk ->
            pm.getPackageArchiveInfo(apk.path, 0)?.takeIf { it.splitNames.isNullOrEmpty() }?.let { apk to it }
        } ?: apks.firstNotNullOfOrNull { apk -> pm.getPackageArchiveInfo(apk.path, 0)?.let { apk to it } }
            ?: error("Not an Android app")
        val (baseFile, info) = base
        val label = info.applicationInfo?.let { ai ->
            ai.sourceDir = baseFile.path
            ai.publicSourceDir = baseFile.path
            ai.loadLabel(pm).toString()
        } ?: info.packageName

        _status.value = InstallStatus.Working("Installing $label…")
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(info.packageName)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apks.forEachIndexed { i, apk ->
                apk.inputStream().use { input ->
                    session.openWrite("part$i.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
            }
            val callback = Intent(ACTION_RESULT).setPackage(context.packageName)
                .putExtra(EXTRA_LABEL, label).putExtra(EXTRA_PACKAGE, info.packageName)
            val pending = PendingIntent.getBroadcast(context, id, callback, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
        dir.deleteRecursively()
    }

    /** A plain APK is returned as is; a bundle's APK entries are extracted next to it. */
    private fun splitApks(file: File, dir: File): List<File> = ZipFile(file).use { zip ->
        if (zip.getEntry("AndroidManifest.xml") != null) return listOf(file)
        zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
            .mapIndexed { i, entry ->
                File(dir, "split$i.apk").also { out -> zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } } }
            }.toList()
    }

    /** Session results: ask the user to confirm, or record the newly installed app. */
    class ResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
            val pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // The typed overload is API 33; minSdk is 31, so fall back to the deprecated one.
                    val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    if (confirm == null) {
                        _status.value = InstallStatus.Failed("Android didn't ask to confirm the install")
                        return
                    }
                    _status.value = InstallStatus.Confirming(label)
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    PortedApps.get(context).add(pkg, label)
                    _status.value = InstallStatus.Installed(pkg, label)
                }
                else -> _status.value = InstallStatus.Failed(
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: describe(status),
                )
            }
        }

        private fun describe(status: Int) = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Install cancelled"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Install blocked"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "Conflicts with an installed app (signed by someone else?)"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Not compatible with this phone"
            PackageInstaller.STATUS_FAILURE_INVALID -> "The APK is damaged or incomplete"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage"
            else -> "Install failed"
        }
    }
}

/** Label of an installed package, or null if it isn't installed. */
fun PackageManager.labelOf(packageName: String): String? = runCatching {
    getApplicationInfo(packageName, 0).loadLabel(this).toString()
}.getOrNull()
