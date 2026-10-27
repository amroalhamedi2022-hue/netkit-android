package com.netkit.local.share

fun main() {
    run {
        val ceiling = 2048L
        UploadLengthPolicy.check(0L, ceiling)
        UploadLengthPolicy.check(ceiling, ceiling)
        for (invalid in listOf(-1L, ceiling + 1L, Long.MAX_VALUE)) {
            check(runCatching { UploadLengthPolicy.check(invalid, ceiling) }.isFailure) {
                "Accepted invalid declared upload length: $invalid"
            }
        }
        check(runCatching { UploadLengthPolicy.check(0L, 0L) }.isFailure)
    }
    check(UploadPathPolicy.folders("Pictures/2026/صورة-😀.png", "صورة-😀.png") ==
        listOf("Pictures", "2026"))
    check(UploadPathPolicy.folders("Books/Ortho.pdf", "Ortho.pdf") == listOf("Books"))
    check(UploadPathPolicy.validName("تقرير نهائي.pdf"))
    // Arabic and emoji joiner sequences remain valid, but invisible tag and
    // soft-hyphen tricks must not enter SAF folder names.
    check(UploadPathPolicy.validName("family-👨‍👩‍👧.png"))
    check(UploadPathPolicy.validName("می‌خواهم.txt"))
    for (bad in listOf("invoice\u00ad.pdf", "name\u034f.txt", "name\u180e.txt",
        "name\u200b.txt", "name\u2060.txt", "name\uDB40\uDC20.txt")) {
        check(!UploadPathPolicy.validName(bad)) { "Accepted invisible filename: $bad" }
        check(runCatching { UploadPathPolicy.folders("Shared/$bad", bad) }.isFailure)
    }
    for (path in listOf(
        "", "file.txt", "/file.txt", "a//file.txt", "a/../file.txt",
        "a/./file.txt", "a\\b/file.txt", "a/CON.txt", "a/.netkit-secret/file.txt",
        "a/invoice\u202Egpj.exe", "a/file.txt/", "a/file.txt/extra",
        "a/" + "deep/".repeat(17) + "file.txt",
        "a/" + "x".repeat(1024) + "/file.txt"
    )) {
        check(runCatching { UploadPathPolicy.folders(path, "file.txt") }.isFailure) {
            "Accepted unsafe path: $path"
        }
    }
    check(runCatching { UploadPathPolicy.folders("Pictures/one.txt", "other.txt") }.isFailure)
    check(runCatching { UploadPathPolicy.checkFolders(emptyList(), "file.txt") }.isFailure)
    check(runCatching { UploadPathPolicy.checkFolders(listOf("ok", ".."), "file.txt") }.isFailure)

    val names = UploadNameReservations()
    val root = names.reserve("file.txt") { false }
    val inA = names.reserve("file.txt", "A") { false }
    val inB = names.reserve("file.txt", "B") { false }
    val secondInA = names.reserve("file.txt", "A") { false }
    check(root == "file.txt" && inA == "file.txt" && inB == "file.txt")
    check(secondInA == "file (2).txt")
    names.release(root)
    names.release(inA, "A")
    names.release(inB, "B")
    names.release(secondInA, "A")
    println("PASS: safe relative folder paths, Arabic names, depth quotas, scoped reservations")
}
