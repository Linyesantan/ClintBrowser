package com.jhaiian.clint.cloud

import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/**
 * Bookmark model used by bookmark sync, plus reading and writing the XBEL file format that the
 * Floccus browser extension uses in "WebDAV / XBEL" mode.
 *
 * Folders are identified by their path of names from the root, bookmarks by their URL (the local
 * database keeps one bookmark per URL). The root folder is the top level of the XBEL file.
 */
data class SyncBookmark(val url: String, val title: String, val path: List<String>)

data class SyncTree(
    val bookmarks: Map<String, SyncBookmark>,
    val folders: Set<List<String>>
) {
    companion object {
        val EMPTY = SyncTree(emptyMap(), emptySet())
    }
}

/** Everything read from the remote XBEL file: the tree plus what is needed to write it back faithfully. */
class RemoteXbel(
    val tree: SyncTree,
    /** Bookmarks whose URL already appeared earlier in the file (Floccus allows that, the phone does not). Written back untouched. */
    val extras: List<SyncBookmark>,
    val extraIds: List<Int>,
    val folderIds: Map<List<String>, Int>,
    val bookmarkIds: Map<String, Int>,
    /** Position of every item inside its parent, keyed by [BookmarkXbel.folderKey] / [BookmarkXbel.bookmarkKey]. */
    val order: Map<String, Int>,
    val highestId: Int
) {
    companion object {
        val EMPTY = RemoteXbel(SyncTree.EMPTY, emptyList(), emptyList(), emptyMap(), emptyMap(), emptyMap(), 0)
    }
}

class XbelParseException(message: String) : Exception(message)

object BookmarkXbel {

    private const val DOCTYPE =
        "<!DOCTYPE xbel PUBLIC \"+//IDN python.org//DTD XML Bookmark Exchange Language 1.0//EN//XML\" " +
            "\"http://pyxml.sourceforge.net/topics/dtds/xbel.dtd\">"
    private val HIGHEST_ID_REGEX = Regex("<!---?\\s*highestId\\s*:(\\d+):")

    fun folderKey(path: List<String>) = "F:" + path.joinToString("\u0000")
    fun bookmarkKey(url: String) = "B:$url"

    private class Node(
        val isFolder: Boolean,
        val id: Int?,
        var title: String,
        val href: String,
        val children: MutableList<Node> = mutableListOf()
    )

    fun parse(xml: String): RemoteXbel {
        if (xml.isBlank()) return RemoteXbel.EMPTY
        val root = Node(true, 0, "", "")
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(xml.reader())
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT && !(event == XmlPullParser.START_TAG && parser.name == "xbel")) {
                event = parser.next()
            }
            if (event == XmlPullParser.END_DOCUMENT) throw XbelParseException("no <xbel> element")
            readChildren(parser, root, "xbel")
        } catch (e: XbelParseException) {
            throw e
        } catch (e: Exception) {
            throw XbelParseException(e.message ?: e.javaClass.simpleName)
        }

        val bookmarks = LinkedHashMap<String, SyncBookmark>()
        val folders = LinkedHashSet<List<String>>()
        val extras = mutableListOf<SyncBookmark>()
        val extraIds = mutableListOf<Int>()
        val folderIds = HashMap<List<String>, Int>()
        val bookmarkIds = HashMap<String, Int>()
        val order = HashMap<String, Int>()
        var maxId = HIGHEST_ID_REGEX.find(xml)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        fun walk(node: Node, path: List<String>) {
            node.children.forEachIndexed { index, child ->
                child.id?.let { if (it > maxId) maxId = it }
                if (child.isFolder) {
                    val childPath = path + child.title
                    folders.add(childPath)
                    child.id?.let { folderIds.putIfAbsent(childPath, it) }
                    order.putIfAbsent(folderKey(childPath), index)
                    walk(child, childPath)
                } else {
                    val url = child.href.trim()
                    if (url.isEmpty()) return@forEachIndexed
                    val bookmark = SyncBookmark(url, child.title, path)
                    if (bookmarks.containsKey(url)) {
                        extras.add(bookmark)
                        extraIds.add(child.id ?: -1)
                        order["E:${extras.size - 1}"] = index
                    } else {
                        bookmarks[url] = bookmark
                        child.id?.let { bookmarkIds[url] = it }
                        order[bookmarkKey(url)] = index
                    }
                }
            }
        }
        walk(root, emptyList())
        return RemoteXbel(SyncTree(bookmarks, folders), extras, extraIds, folderIds, bookmarkIds, order, maxId)
    }

    private fun readChildren(parser: XmlPullParser, into: Node, endTag: String) {
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) return
            if (event == XmlPullParser.END_TAG && parser.name == endTag) return
            if (event != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "folder" -> {
                    val node = Node(true, parser.getAttributeValue(null, "id")?.trim()?.toIntOrNull(), "", "")
                    readChildren(parser, node, "folder")
                    into.children.add(node)
                }
                "bookmark" -> {
                    val node = Node(
                        false,
                        parser.getAttributeValue(null, "id")?.trim()?.toIntOrNull(),
                        "",
                        parser.getAttributeValue(null, "href") ?: ""
                    )
                    readChildren(parser, node, "bookmark")
                    into.children.add(node)
                }
                "title" -> into.title = parser.nextText().trim()
                else -> skip(parser)
            }
        }
    }

    private fun skip(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /**
     * Writes [tree] as a Floccus compatible XBEL file. Items that came from [remote] keep their id and
     * their position; new items get fresh ids and go after the existing ones.
     */
    fun serialize(tree: SyncTree, remote: RemoteXbel): String {
        // Folders that must exist: every folder in the tree plus every ancestor of anything in it.
        val allFolders = LinkedHashSet<List<String>>()
        fun addWithAncestors(path: List<String>) {
            for (i in 1..path.size) allFolders.add(path.subList(0, i).toList())
        }
        tree.folders.forEach { addWithAncestors(it) }
        tree.bookmarks.values.forEach { addWithAncestors(it.path) }

        val extras = remote.extras.indices.filter { i ->
            val e = remote.extras[i]
            e.path.isEmpty() || e.path in allFolders
        }

        val childFolders = allFolders.groupBy { it.dropLast(1) }
        val childBookmarks = tree.bookmarks.values.groupBy { it.path }
        val childExtras = extras.groupBy { remote.extras[it].path }

        // Ids kept from the remote file; new items get ids above all of them.
        val reserved = HashSet<Int>().apply {
            allFolders.forEach { p -> remote.folderIds[p]?.let { add(it) } }
            tree.bookmarks.keys.forEach { u -> remote.bookmarkIds[u]?.let { add(it) } }
            extras.forEach { i -> remote.extraIds[i].takeIf { it > 0 }?.let { add(it) } }
        }
        var nextId = maxOf(remote.highestId, reserved.maxOrNull() ?: 0)
        val usedIds = HashSet<Int>()
        fun idFor(existing: Int?): Int {
            if (existing != null && existing > 0 && existing in reserved && usedIds.add(existing)) return existing
            while (true) {
                nextId++
                if (nextId !in reserved && usedIds.add(nextId)) return nextId
            }
        }

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append(DOCTYPE).append('\n')
        sb.append("<xbel version=\"1.0\">\n")
        val body = StringBuilder()

        fun writeFolder(path: List<String>, indent: String) {
            data class Item(val sortKey: Int, val tie: String, val write: () -> Unit)
            val items = mutableListOf<Item>()
            childFolders[path].orEmpty().forEach { folder ->
                val pos = remote.order[folderKey(folder)] ?: Int.MAX_VALUE
                items.add(Item(pos, "0" + folder.last().lowercase(), {
                    val id = idFor(remote.folderIds[folder])
                    body.append(indent).append("<folder id=\"").append(id).append("\">\n")
                    body.append(indent).append("  <title>").append(escape(folder.last())).append("</title>\n")
                    writeFolder(folder, "$indent  ")
                    body.append(indent).append("</folder>\n")
                }))
            }
            childBookmarks[path].orEmpty().forEach { bm ->
                val pos = remote.order[bookmarkKey(bm.url)] ?: Int.MAX_VALUE
                items.add(Item(pos, "1" + bm.title.lowercase(), {
                    writeBookmark(body, indent, idFor(remote.bookmarkIds[bm.url]), bm)
                }))
            }
            childExtras[path].orEmpty().forEach { i ->
                val pos = remote.order["E:$i"] ?: Int.MAX_VALUE
                items.add(Item(pos, "2", {
                    writeBookmark(body, indent, idFor(remote.extraIds[i].takeIf { it > 0 }), remote.extras[i])
                }))
            }
            items.sortedWith(compareBy<Item>({ it.sortKey }, { it.tie })).forEach { it.write() }
        }
        writeFolder(emptyList(), "")

        val highest = nextId
        sb.append("<!--- highestId :").append(highest).append(": for Floccus bookmark sync browser extension -->\n")
        sb.append(body)
        sb.append("</xbel>\n")
        return sb.toString()
    }

    private fun writeBookmark(body: StringBuilder, indent: String, id: Int, bm: SyncBookmark) {
        body.append(indent).append("<bookmark href=\"").append(escape(bm.url)).append("\" id=\"").append(id).append("\">\n")
        body.append(indent).append("  <title>").append(escape(bm.title)).append("</title>\n")
        body.append(indent).append("</bookmark>\n")
    }

    private fun escape(s: String): String {
        val out = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&apos;")
                else -> if (ch == '\t' || ch == '\n' || ch == '\r' || ch.code >= 0x20) out.append(ch)
            }
        }
        return out.toString()
    }
}
