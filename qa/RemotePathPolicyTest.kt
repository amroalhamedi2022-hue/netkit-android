import com.netkit.local.remote.RemotePathPolicy

fun main() {
    fun rejectPath(value: String) {
        check(runCatching { RemotePathPolicy.path(value) }.isFailure) {
            "Unsafe FTP/SFTP path accepted: ${value.toList().map { it.code }}"
        }
    }
    listOf("", "file\r\nDELE secret", "x\u0000y", "x\u007fy", "x\u0085y",
        "invoice\u202Egpj.exe", "name\u2066.txt", "x\uFFFDy",
        "broken\uD800", "broken\uDC00", "a".repeat(4097)).forEach(::rejectPath)
    check(RemotePathPolicy.path("/ملفات/📁 صورة 01.jpg") == "/ملفات/📁 صورة 01.jpg")
    // Invisible Unicode spoofing must be rejected before FTP commands or SFTP
    // listings reuse the name. Supplementary tags require code-point checks.
    for (unsafe in listOf("\u00AD", "\u034F", "\u180E", "\u200B", "\u200C",
        "\u2028", "\u2029", "\u2060", "\u206A", "\uFEFF", "\uFFF9",
        "\uDB40\uDC20", "\uDB40\uDC7F")) {
        rejectPath("report" + unsafe + ".txt")
        check(!RemotePathPolicy.safeEntryName("report" + unsafe + ".txt"))
        check(runCatching { RemotePathPolicy.credential("user" + unsafe) }.isFailure)
    }
    // Keep Arabic, emoji and emoji ZWJ sequences (not a generic ASCII filter).
    check(RemotePathPolicy.path("/ملفات/👩‍💻 مشروع.txt") == "/ملفات/👩‍💻 مشروع.txt")
    check(RemotePathPolicy.path("../parent/normal.txt") == "../parent/normal.txt")
    check(RemotePathPolicy.path("/") == "/")
    check(RemotePathPolicy.credential("", allowEmpty = true).isEmpty())
    check(runCatching { RemotePathPolicy.credential("") }.isFailure)
    check(runCatching { RemotePathPolicy.credential("user\nSITE CHMOD") }.isFailure)
    check(runCatching { RemotePathPolicy.credential("pass\r\nDELE /") }.isFailure)
    check(RemotePathPolicy.credential("كلمة مرور 🫧") == "كلمة مرور 🫧")
    for (host in listOf("", "example.com\r\n", "a b", "evil/host", "host\u202E", "a".repeat(254))) {
        check(runCatching { RemotePathPolicy.host(host) }.isFailure)
    }
    check(RemotePathPolicy.host("192.168.1.20") == "192.168.1.20")
    check(RemotePathPolicy.host("fe80::1") == "fe80::1")
    check(RemotePathPolicy.safeEntryName("ملف 🫧.txt"))
    for (name in listOf(".", "..", "x/y", "x\\y", "x\r\nDELE z", "x\u202Etxt")) {
        check(!RemotePathPolicy.safeEntryName(name)) { "Unsafe server entry accepted: $name" }
    }
    println("FTP/SFTP remote path, credential and listing safety: PASS")
}
