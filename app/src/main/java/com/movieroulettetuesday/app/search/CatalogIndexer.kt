package com.movieroulettetuesday.app.search

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes [CatalogDocuments] into Jetpack AppSearch (platform storage on API 31+). Failures are
 * swallowed: in-app Movies and Episodes search stays the reliable path, and Robolectric / older
 * devices simply have no system index. Personal data is never passed in.
 *
 * Play-services storage is intentionally not used: this app has no Play services dependency and
 * does not add one for search. On API 26–30, and on devices where platform storage is incomplete,
 * this is a no-op.
 */
class CatalogIndexer(
    private val context: Context,
    private val enabled: Boolean = Build.VERSION.SDK_INT >= 31,
    private val engine: Engine? = if (enabled) AppSearchEngine(context) else null,
) {
    interface Engine {
        suspend fun replaceAll(docs: List<CatalogDocument>)
    }

    suspend fun reindex(docs: List<CatalogDocument>) {
        val e = engine ?: return
        runCatching { e.replaceAll(docs) }
            .onFailure { Log.w(TAG, "AppSearch reindex skipped: ${it.message}") }
    }

    /**
     * Platform AppSearch, loaded reflectively so a missing library or an unsupported device
     * cannot break the build or a test run.
     */
    class AppSearchEngine(private val context: Context) : Engine {
        override suspend fun replaceAll(docs: List<CatalogDocument>) = withContext(Dispatchers.IO) {
            val session = openSession() ?: return@withContext
            try {
                setSchema(session)
                // Clear previous docs by putting a fresh set after a schema-compatible replace.
                val putClass = Class.forName("androidx.appsearch.app.PutDocumentsRequest\$Builder")
                val putBuilder = putClass.getConstructor().newInstance()
                val add = putClass.methods.first { it.name == "addGenericDocuments" && it.parameterTypes.size == 1 && it.parameterTypes[0].isArray }
                val genericDocs = docs.map { toGeneric(it) }.toTypedArray()
                // The array needs to be GenericDocument[].
                val array = java.lang.reflect.Array.newInstance(genericDocs.firstOrNull()?.javaClass ?: Class.forName("androidx.appsearch.app.GenericDocument"), genericDocs.size)
                genericDocs.forEachIndexed { i, d -> java.lang.reflect.Array.set(array, i, d) }
                if (genericDocs.isNotEmpty()) add.invoke(putBuilder, array)
                val request = putClass.getMethod("build").invoke(putBuilder)
                val putAsync = session.javaClass.methods.first { it.name == "putAsync" }
                await(putAsync.invoke(session, request))
            } finally {
                runCatching { session.javaClass.getMethod("close").invoke(session) }
            }
        }

        private fun openSession(): Any? = runCatching {
            if (Build.VERSION.SDK_INT < 31) return@runCatching null
            val storage = Class.forName("androidx.appsearch.platformstorage.PlatformStorage")
            val ctxClass = Class.forName("androidx.appsearch.platformstorage.PlatformStorage\$SearchContext\$Builder")
            val ctx = ctxClass.getConstructor(Context::class.java, String::class.java)
                .newInstance(context, "mrt-catalog")
            val searchCtx = ctxClass.getMethod("build").invoke(ctx)
            val future = storage.getMethod("createSearchSessionAsync", Class.forName("androidx.appsearch.platformstorage.PlatformStorage\$SearchContext"))
                .invoke(null, searchCtx)
            await(future)
        }.getOrNull()

        private fun setSchema(session: Any) {
            val schemaClass = Class.forName("androidx.appsearch.app.AppSearchSchema\$Builder")
            val movie = schema(schemaClass, CatalogDocuments.MOVIE_SCHEMA, stringProps = listOf("title", "genre", "url"), longProps = listOf("year"))
            val episode = schema(schemaClass, CatalogDocuments.EPISODE_SCHEMA, stringProps = listOf("title", "url"), longProps = listOf("season"))
            val reqClass = Class.forName("androidx.appsearch.app.SetSchemaRequest\$Builder")
            val req = reqClass.getConstructor().newInstance()
            val addSchemas = reqClass.methods.first { it.name == "addSchemas" && it.parameterTypes.isNotEmpty() }
            val schemaType = Class.forName("androidx.appsearch.app.AppSearchSchema")
            val arr = java.lang.reflect.Array.newInstance(schemaType, 2)
            java.lang.reflect.Array.set(arr, 0, movie)
            java.lang.reflect.Array.set(arr, 1, episode)
            addSchemas.invoke(req, arr)
            runCatching {
                reqClass.getMethod("setSchemaTypeDisplayedBySystem", String::class.java, Boolean::class.javaPrimitiveType)
                    .invoke(req, CatalogDocuments.MOVIE_SCHEMA, true)
                reqClass.getMethod("setSchemaTypeDisplayedBySystem", String::class.java, Boolean::class.javaPrimitiveType)
                    .invoke(req, CatalogDocuments.EPISODE_SCHEMA, true)
            }
            // Force a reset so dropped titles disappear: setForceOverride true.
            runCatching { reqClass.getMethod("setForceOverride", Boolean::class.javaPrimitiveType).invoke(req, true) }
            val request = reqClass.getMethod("build").invoke(req)
            val setAsync = session.javaClass.methods.first { it.name == "setSchemaAsync" }
            await(setAsync.invoke(session, request))
        }

        private fun schema(builderClass: Class<*>, name: String, stringProps: List<String>, longProps: List<String>): Any {
            val b = builderClass.getConstructor(String::class.java).newInstance(name)
            val strCfg = Class.forName("androidx.appsearch.app.AppSearchSchema\$StringPropertyConfig\$Builder")
            stringProps.forEach { prop ->
                val pb = strCfg.getConstructor(String::class.java).newInstance(prop)
                runCatching {
                    // INDEXING_TYPE_PREFIX = 2 typically; look up the constant.
                    val idx = Class.forName("androidx.appsearch.app.AppSearchSchema\$StringPropertyConfig")
                        .getField("INDEXING_TYPE_PREFIX").getInt(null)
                    strCfg.getMethod("setIndexingType", Int::class.javaPrimitiveType).invoke(pb, idx)
                    val tok = Class.forName("androidx.appsearch.app.AppSearchSchema\$StringPropertyConfig")
                        .getField("TOKENIZER_TYPE_PLAIN").getInt(null)
                    strCfg.getMethod("setTokenizerType", Int::class.javaPrimitiveType).invoke(pb, tok)
                }
                val cfg = strCfg.getMethod("build").invoke(pb)
                builderClass.methods.first { it.name == "addProperty" }.invoke(b, cfg)
            }
            val longCfg = Class.forName("androidx.appsearch.app.AppSearchSchema\$LongPropertyConfig\$Builder")
            longProps.forEach { prop ->
                val pb = longCfg.getConstructor(String::class.java).newInstance(prop)
                val cfg = longCfg.getMethod("build").invoke(pb)
                builderClass.methods.first { it.name == "addProperty" }.invoke(b, cfg)
            }
            return builderClass.getMethod("build").invoke(b)
        }

        private fun toGeneric(doc: CatalogDocument): Any {
            val bClass = Class.forName("androidx.appsearch.app.GenericDocument\$Builder")
            // GenericDocument.Builder(namespace, id, schemaType)
            val b = bClass.getConstructor(String::class.java, String::class.java, String::class.java)
                .newInstance(CatalogDocuments.NAMESPACE, doc.id, doc.schema)
            bClass.getMethod("setPropertyString", String::class.java, Array<String>::class.java)
                .invoke(b, "title", arrayOf(doc.title))
            bClass.getMethod("setPropertyString", String::class.java, Array<String>::class.java)
                .invoke(b, "url", arrayOf(doc.uri))
            doc.genre?.let {
                bClass.getMethod("setPropertyString", String::class.java, Array<String>::class.java)
                    .invoke(b, "genre", arrayOf(it))
            }
            doc.year?.let {
                bClass.getMethod("setPropertyLong", String::class.java, LongArray::class.java)
                    .invoke(b, "year", longArrayOf(it.toLong()))
            }
            doc.season?.let {
                bClass.getMethod("setPropertyLong", String::class.java, LongArray::class.java)
                    .invoke(b, "season", longArrayOf(it.toLong()))
            }
            // Document score unused; url is the deep link a system surface can open.
            return bClass.getMethod("build").invoke(b)
        }

        private fun await(future: Any?): Any? {
            if (future == null) return null
            // com.google.common.util.concurrent.ListenableFuture, or java Future
            val get = future.javaClass.methods.firstOrNull { it.name == "get" && it.parameterCount == 0 } ?: return null
            return get.invoke(future)
        }
    }

    companion object {
        private const val TAG = "MrtCatalogIndexer"
    }
}
