package eu.kanade.tachiyomi.extension.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import dalvik.system.PathClassLoader
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.util.Hash
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.runBlocking
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@SuppressLint("PackageManagerGetSignatures")
internal object ExtensionLoader {

    private const val EXTENSION_FEATURE = "tachiyomi.extension"
    private const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
    private const val LIB_VERSION_MIN = 1
    private const val LIB_VERSION_MAX = 1

    private const val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or PackageManager.GET_SIGNATURES

    var trustedSignatures = mutableSetOf<String>() +
            Injekt.get<PreferencesHelper>().trustedSignatures().getOrDefault() +
            // inorichi's key
            "7ce04da7773d41b489f4693a366c36bcd0a11fc39b547168553c285bd7348e23"

    fun loadExtensions(context: Context): List<LoadResult> {
        val pkgManager = context.packageManager
        val installedPkgs = pkgManager.getInstalledPackages(PACKAGE_FLAGS)
        val extPkgs = installedPkgs.filter { isPackageAnExtension(it) }

        if (extPkgs.isEmpty()) return emptyList()

        // Load each extension concurrently and wait for completion
        return runBlocking {
            val deferred = extPkgs.map {
                async { loadExtension(context, it.packageName, it) }
            }
            deferred.map { it.await() }
        }
    }

    fun loadExtensionFromPkgName(context: Context, pkgName: String): LoadResult {
        val pkgInfo = context.packageManager.getPackageInfo(pkgName, PACKAGE_FLAGS)
        if (!isPackageAnExtension(pkgInfo)) {
            return LoadResult.Error("Tried to load a package that wasn't a extension")
        }
        return loadExtension(context, pkgName, pkgInfo)
    }

    private fun loadExtension(context: Context, pkgName: String, optPkgInfo: PackageInfo? = null): LoadResult {
        val pkgManager = context.packageManager

        val pkgInfo = optPkgInfo ?: pkgManager.getPackageInfo(pkgName, PACKAGE_FLAGS)
        val appInfo = pkgManager.getApplicationInfo(pkgName, PackageManager.GET_META_DATA)

        val extName = pkgManager.getApplicationLabel(appInfo).toString().substringAfter("Tachiyomi: ")
        val versionName = pkgInfo.versionName
        val versionCode = pkgInfo.versionCode

        // Validate lib version
        val majorLibVersion = versionName.substringBefore('.').toInt()
        if (majorLibVersion < LIB_VERSION_MIN || majorLibVersion > LIB_VERSION_MAX) {
            val exception = Exception("Lib version is $majorLibVersion, while only versions " +
                    "$LIB_VERSION_MIN to $LIB_VERSION_MAX are allowed")
            Timber.w(exception)
            return LoadResult.Error(exception)
        }

        val signatures = pkgInfo.signatures
        val signatureHash = if (signatures != null && !signatures.isEmpty()) {
            Hash.sha256(signatures.first().toByteArray())
        } else {
            null
        }

        if (signatureHash == null) {
            return LoadResult.Error("Package $pkgName isn't signed")
        } else if (signatureHash !in trustedSignatures) {
            val extension = Extension.Untrusted(extName, pkgName, versionName, versionCode, signatureHash)
            Timber.w("Extension $pkgName isn't trusted")
            return LoadResult.Untrusted(extension)
        }

        val classLoader = PathClassLoader(appInfo.sourceDir, null, context.classLoader)

        val sources = appInfo.metaData.getString(METADATA_SOURCE_CLASS)
                .split(";")
                .map {
                    val sourceClass = it.trim()
                    if (sourceClass.startsWith("."))
                        pkgInfo.packageName + sourceClass
                    else
                        sourceClass
                }
                .flatMap {
                    try {
                        val obj = Class.forName(it, false, classLoader).newInstance()
                        when (obj) {
                            is Source -> listOf(obj)
                            is SourceFactory -> obj.createSources()
                            else -> throw Exception("Unknown source class type! ${obj.javaClass}")
                        }
                    } catch (e: Throwable) {
                        Timber.e(e, "Extension load error: $extName.")
                        return LoadResult.Error(e)
                    }
                }
        val langs = sources.filterIsInstance<CatalogueSource>()
                .map { it.lang }
                .toSet()

        val lang = when (langs.size) {
            0 -> ""
            1 -> langs.first()
            else -> "all"
        }

        val extension = Extension.Installed(extName, pkgName, versionName, versionCode, sources, lang)
        return LoadResult.Success(extension)
    }

    private fun isPackageAnExtension(pkgInfo: PackageInfo): Boolean {
        return pkgInfo.reqFeatures.orEmpty().any { it.name == EXTENSION_FEATURE }
    }

}