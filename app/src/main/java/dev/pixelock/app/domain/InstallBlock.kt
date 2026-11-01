package dev.pixelock.app.domain

/** An app kept off this phone. [packages] is empty for a typed name that is not installed. */
data class BannedApp(
    val name: String = "",
    val packages: List<String> = emptyList()
)

private const val INSTALL_WORD = "Install"
private val TITLE_SEPARATORS = setOf(':', '-', '|', ',', '(', '\u2013', '\u2014')

/** Stores add taglines. Name alone or name then separator, so Facebook skips Facebook Messenger. */
fun isAppTitle(text: String, name: String): Boolean {
    val title = text.trim()
    if (name.isBlank() || !title.startsWith(name, ignoreCase = true)) return false
    val rest = title.substring(name.length).trimStart()
    return rest.isEmpty() || rest.first() in TITLE_SEPARATORS
}

/**
 * The banned app this store or installer page offers, or null. [screen] is its visible text. An
 * [installer] page exists only to install, and hides its button, so the name alone is enough there.
 */
// ponytail: English word only. A store in another language falls back to the block at open.
fun bannedOnInstallPage(screen: List<String>, banned: List<BannedApp>, installer: Boolean = false): BannedApp? {
    if (!installer && screen.none { it.trim().equals(INSTALL_WORD, ignoreCase = true) }) return null
    return banned.firstOrNull { app -> screen.any { isAppTitle(it, app.name) } }
}

/** A page that can remove or stop [label]. Plain lists name it too, and those stay open. */
// ponytail: the system words in the table are English. Admin and accessibility pages still match in
// any language through this app's own text, and uninstall always lands on the admin page first.
fun isRemovalPage(screen: List<String>, label: String, words: List<String>): Boolean =
    screen.any { it.contains(label, ignoreCase = true) } &&
        screen.any { text -> words.any { text.contains(it, ignoreCase = true) } }
