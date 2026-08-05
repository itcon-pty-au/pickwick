package io.pickwick.app.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import java.io.File
import java.security.MessageDigest

/**
 * Parent-sideloaded videos: files the parent already has on this phone (home
 * videos, rips, purchases), linked — never copied — onto the kid's Downloads
 * shelf. SAF document URIs only: no storage permission dialog, and the parent
 * grants exactly the folder the kid may see, not the whole camera roll.
 *
 * Device-local by design. Document URIs are meaningless on any other device,
 * so nothing here goes into config.json or the LAN sync — a TV would show
 * ghost entries it can never play.
 *
 * Identity: each file gets a synthetic page URL `pickwick://local/<id>` (id =
 * hash of the document URI). That URL is the app-wide key, so watch history,
 * resume and stats work unchanged; Video.videoId recognizes the prefix.
 */
class LocalLibrary(private val context: Context) {

    /** One linked video; the folder name rides in video.channelName. */
    data class Item(
        val video: Video,
        val docUri: String,
        /** The granted folder this came from; empty for individually-picked files. */
        val treeUri: String,
        val addedAt: Long,
        /** File still present at the last scan. Missing files are kept (greyed
         *  in settings, hidden from the kid) — deleting them would drop the
         *  kid's resume position over what may be an unmounted SD card. */
        val available: Boolean
    )

    /** A folder the parent granted; rescanned to pick up new files.
     *  [profileIds] empty = every kid sees its videos (matches WhitelistEntry). */
    data class Tree(val uri: String, val name: String, val profileIds: Set<String> = emptySet())

    private val root = File(context.filesDir, "local").apply { mkdirs() }
    private val index = File(root, "index.tsv")
    private val treesFile = File(root, "trees.tsv")
    private val thumbs = File(root, "thumbs").apply { mkdirs() }

    fun items(): List<Item> = synchronized(LOCK) { loadItems() }

    fun trees(): List<Tree> = synchronized(LOCK) { loadTrees() }

    /** Videos for the kid's Downloads shelf: available only, grouped by folder.
     *  [profileId] filters folder videos to that kid; picked files show for all. */
    fun videos(profileId: String? = null): List<Video> = visibleItems(profileId)
        .map { it.video }
        .sortedWith(compareBy({ it.channelName }, { it.title }))

    /** Playable page URLs (joins DownloadStore.downloadedUrls for the ✅ set). */
    fun urls(profileId: String? = null): Set<String> = visibleItems(profileId)
        .map { it.video.url }
        .toSet()

    private fun visibleItems(profileId: String?): List<Item> {
        val treeVisibility = trees().associate { it.uri to it.profileIds }
        return items().filter { item ->
            item.available && run {
                val pids = treeVisibility[item.treeUri].orEmpty()
                pids.isEmpty() || profileId == null || profileId in pids
            }
        }
    }

    /** Local stream for the player, or null if this URL isn't a linked file. */
    fun playback(videoUrl: String): YouTubeRepository.Playback? {
        if (!isLocal(videoUrl)) return null
        val item = items().firstOrNull { it.video.url == videoUrl && it.available } ?: return null
        return YouTubeRepository.Playback(item.video.title, item.docUri, null, emptyList())
    }

    /**
     * Link a granted folder. Contents arrive via the rescan() the caller runs
     * next — adding and scanning are split so the UI can narrate the slow part.
     */
    fun addTree(uri: Uri, profileIds: Set<String> = emptySet()) {
        // Persist the grant or the link dies with the next reboot. Some
        // providers refuse; the link then just lives until reboot.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val s = uri.toString()
        synchronized(LOCK) {
            val all = loadTrees()
            if (all.any { it.uri == s }) return
            saveTrees(all + Tree(s, treeName(uri), profileIds))
        }
        DownloadEvents.notifyChanged()
    }

    /** Update which kids can see a folder's videos (empty = everyone). */
    fun setTreeProfiles(treeUri: String, profileIds: Set<String>) {
        synchronized(LOCK) {
            saveTrees(loadTrees().map {
                if (it.uri == treeUri) it.copy(profileIds = profileIds) else it
            })
        }
        DownloadEvents.notifyChanged()
    }

    /** Unlink a folder and its videos. The files themselves are untouched. */
    fun forgetTree(treeUri: String) {
        synchronized(LOCK) {
            saveTrees(loadTrees().filterNot { it.uri == treeUri })
            val (gone, keep) = loadItems().partition { it.treeUri == treeUri }
            saveItems(keep)
            gone.forEach { thumbFor(idFor(it.docUri)).delete() }
        }
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        DownloadEvents.notifyChanged()
    }

    /**
     * Link individually-picked files. Each takes one persistable-grant slot
     * (Android caps those per app) — folders are the scalable path.
     */
    fun addFiles(uris: List<Uri>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        var added = 0
        onProgress(0, uris.size)
        uris.forEachIndexed { i, uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val docUri = uri.toString()
            val known = synchronized(LOCK) { loadItems().any { it.docUri == docUri } }
            if (!known) {
                val meta = extractMeta(uri, displayName(uri) ?: docUri.substringAfterLast('/'))
                val item = Item(
                    Video(
                        URL_PREFIX + idFor(docUri), meta.title, folderForDoc(uri),
                        meta.thumbUrl, meta.durationSeconds
                    ),
                    docUri, treeUri = "", System.currentTimeMillis(), available = true
                )
                synchronized(LOCK) { saveItems(loadItems() + item) }
                added++
            }
            onProgress(i + 1, uris.size)
        }
        DownloadEvents.notifyChanged()
        return added
    }

    /**
     * Walk every linked folder: new video files are added (metadata + thumbnail
     * extracted — the slow part, reported via onProgress), vanished ones are
     * marked unavailable, returned ones come back. Individually-picked files
     * get a cheap existence probe. Returns how many new videos were found.
     */
    fun rescan(onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val found = trees().flatMap { listVideos(it) }
        val foundUris = found.map { it.docUri }.toSet()
        val known = items().map { it.docUri }.toSet()
        val fresh = found.filter { it.docUri !in known }

        onProgress(0, fresh.size)
        val now = System.currentTimeMillis()
        val newItems = fresh.mapIndexed { i, f ->
            val meta = extractMeta(Uri.parse(f.docUri), f.displayName)
            onProgress(i + 1, fresh.size)
            Item(
                Video(
                    URL_PREFIX + idFor(f.docUri), meta.title, f.folder,
                    meta.thumbUrl, meta.durationSeconds
                ),
                f.docUri, f.treeUri, now, available = true
            )
        }

        synchronized(LOCK) {
            val updated = loadItems().map { item ->
                if (item.treeUri.isEmpty()) item.copy(available = probe(item.docUri))
                else item.copy(available = item.docUri in foundUris)
            }
            saveItems(updated + newItems.filter { n -> updated.none { it.docUri == n.docUri } })
        }
        DownloadEvents.notifyChanged()
        return newItems.size
    }

    /**
     * Unlink one individually-picked file. Folder-scanned videos are managed by
     * their folder instead — a removed row would silently return on rescan.
     */
    fun remove(videoUrl: String) {
        var releaseUri: String? = null
        synchronized(LOCK) {
            val all = loadItems()
            val item = all.firstOrNull { it.video.url == videoUrl && it.treeUri.isEmpty() } ?: return
            saveItems(all - item)
            thumbFor(idFor(item.docUri)).delete()
            releaseUri = item.docUri
        }
        releaseUri?.let {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        DownloadEvents.notifyChanged()
    }

    // --- scanning ------------------------------------------------------------

    private data class Found(
        val docUri: String, val displayName: String, val folder: String, val treeUri: String
    )

    private fun listVideos(tree: Tree): List<Found> {
        val treeUri = Uri.parse(tree.uri)
        val out = mutableListOf<Found>()
        // BFS with the folder's display name carried along: a file's immediate
        // folder becomes its "channel" on the shelf.
        val queue = ArrayDeque<Pair<String, String>>()
        runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrNull()?.let { queue.add(it to tree.name) } ?: return out
        while (queue.isNotEmpty()) {
            val (dirId, folder) = queue.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
            runCatching {
                context.contentResolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1).orEmpty()
                        val mime = c.getString(2).orEmpty()
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            queue.add(id to name)
                        } else if (mime.startsWith("video/")) {
                            out += Found(
                                DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
                                name, folder, tree.uri
                            )
                        }
                    }
                }
            }
        }
        return out
    }

    /** Cheap "does it still exist" for a picked file (grant may be revoked). */
    private fun probe(docUri: String): Boolean = runCatching {
        context.contentResolver.query(
            Uri.parse(docUri),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null, null, null
        )?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    // --- metadata + thumbnails ----------------------------------------------

    private data class Meta(val title: String, val durationSeconds: Long, val thumbUrl: String?)

    private fun extractMeta(uri: Uri, displayName: String): Meta {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: titleFromName(displayName)
            val thumb = writeThumb(mmr, idFor(uri.toString()), durMs)
            Meta(title, durMs / 1000, thumb?.toURI()?.toString())
        } catch (e: Exception) {
            // Unreadable/corrupt file: still listed (it may play), no thumb.
            Meta(titleFromName(displayName), 0L, null)
        } finally {
            // The retriever holds a native decoder — leaking one per file
            // during a big folder scan runs the process out of memory.
            runCatching { mmr.release() }
        }
    }

    private fun writeThumb(mmr: MediaMetadataRetriever, id: String, durationMs: Long): File? {
        // Embedded cover art (tagged rips) beats any frame; otherwise a frame
        // at ~10% in — frame zero is black or a fade-in on most real video.
        val timeUs = durationMs * 100L
        val bmp = mmr.embeddedPicture?.let { decodeArt(it) }
            ?: frameAt(mmr, timeUs)
            ?: return null
        val f = thumbFor(id)
        val ok = runCatching {
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        }.isSuccess
        bmp.recycle()
        return if (ok) f else null
    }

    private fun frameAt(mmr: MediaMetadataRetriever, timeUs: Long): Bitmap? =
        if (Build.VERSION.SDK_INT >= 27) {
            // Decodes straight to target size — a 1080p bitmap per file would
            // make a 200-video scan crawl. Falls back when no sync frame is near.
            mmr.getScaledFrameAtTime(
                timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_W, THUMB_H
            ) ?: mmr.getScaledFrameAtTime(
                timeUs, MediaMetadataRetriever.OPTION_CLOSEST, THUMB_W, THUMB_H
            )
        } else {
            mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        }

    private fun decodeArt(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB_W) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    // --- naming --------------------------------------------------------------

    private fun treeName(treeUri: Uri): String {
        runCatching {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            context.contentResolver.query(
                docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0)?.let { n -> return n } }
        }
        // Fallback: last segment of the doc id, e.g. "primary:Movies/Kids" → "Kids".
        return runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?.substringAfter(':')?.substringAfterLast('/')?.ifEmpty { null } ?: "Videos"
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    /** "Kids/Bluey/ep3.mp4" → "Bluey"; opaque provider ids → generic label. */
    private fun folderForDoc(uri: Uri): String {
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: return "My videos"
        val parent = id.substringAfter(':', "")
            .substringBeforeLast('/', "").substringAfterLast('/')
        return parent.ifEmpty { "My videos" }
    }

    private fun titleFromName(name: String): String =
        name.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { name }

    // --- persistence ----------------------------------------------------------

    private fun thumbFor(id: String): File = File(thumbs, "$id.jpg")

    private fun loadItems(): List<Item> {
        if (!index.exists()) return emptyList()
        return runCatching {
            index.readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 8) return@mapNotNull null
                Item(
                    Video(p[0], p[1], p[2], p[3].ifEmpty { null }, p[4].toLongOrNull() ?: 0L),
                    docUri = p[5], treeUri = p[6],
                    addedAt = p[7].toLongOrNull() ?: 0L,
                    available = p.getOrNull(8)?.toBoolean() ?: true
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveItems(items: List<Item>) {
        runCatching {
            index.writeText(items.joinToString("\n") { it ->
                listOf(
                    it.video.url,
                    it.video.title.replace('\t', ' ').replace('\n', ' '),
                    it.video.channelName.replace('\t', ' ').replace('\n', ' '),
                    it.video.thumbnailUrl.orEmpty(),
                    it.video.durationSeconds.toString(),
                    it.docUri,
                    it.treeUri,
                    it.addedAt.toString(),
                    it.available.toString()
                ).joinToString("\t")
            })
        }
    }

    private fun loadTrees(): List<Tree> {
        if (!treesFile.exists()) return emptyList()
        return runCatching {
            treesFile.readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 2) null else Tree(
                    p[0], p[1],
                    // Third column absent on rows written before profiles existed.
                    p.getOrNull(2)?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveTrees(trees: List<Tree>) {
        runCatching {
            treesFile.writeText(trees.joinToString("\n") {
                "${it.uri}\t${it.name.replace('\t', ' ').replace('\n', ' ')}\t" +
                    it.profileIds.joinToString(",")
            })
        }
    }

    companion object {
        const val URL_PREFIX = "pickwick://local/"

        fun isLocal(url: String): Boolean = url.startsWith(URL_PREFIX)

        /** 16 hex chars: stable per document URI, can't collide with an 11-char YouTube id. */
        fun idFor(docUri: String): String =
            MessageDigest.getInstance("SHA-256").digest(docUri.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(16)

        private const val THUMB_W = 480
        private const val THUMB_H = 270

        /** Constructed in several places (settings UI, player, VM) over one file. */
        private val LOCK = Any()
    }
}
