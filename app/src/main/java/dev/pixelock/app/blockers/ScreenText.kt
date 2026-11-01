package dev.pixelock.app.blockers

import android.view.accessibility.AccessibilityNodeInfo

private const val MAX_NODES = 500

/**
 * Everything readable on a page. Compose pages answer no system search, so this walks. Worker only.
 * Since Android 14 the system hides sensitive views from this service, such as the installer's
 * Install button and device admin rows. Only an accessibility tool sees those, and this app is not one.
 */
fun AccessibilityNodeInfo.visibleTexts(): List<String> {
    val found = ArrayList<String>()
    val pending = ArrayDeque<AccessibilityNodeInfo>()
    pending.addLast(this)
    var seen = 0
    while (pending.isNotEmpty() && seen++ < MAX_NODES) {
        val node = pending.removeFirst()
        if (node.isVisibleToUser) {
            node.text?.let { found += it.toString() }
            node.contentDescription?.let { found += it.toString() }
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let(pending::addLast)
    }
    return found
}
