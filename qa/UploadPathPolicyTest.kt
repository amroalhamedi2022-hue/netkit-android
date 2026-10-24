package com.netkit.local.share

fun main() {
    check(UploadPathPolicy.folders("Pictures/2026/صورة-😀.png", "صورة-😀.png") ==
        listOf("Pictures", "2026"))
    check(UploadPathPolicy.folders("Books/Ortho.pdf", "Ortho.pdf") == listOf("Books"))
    check(UploadPathPolicy.validName("تقرير نهائي.pdf"))
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
    val root = names.reserve("file.txt", { false })
    val inA = names.reserve("file.txt", { false }, "A")
    val inB = names.reserve("file.txt", { false }, "B")
    val secondInA = names.reserve("file.txt", { false }, "A")
    check(root == "file.txt" && inA == "file.txt" && inB == "file.txt")
    check(secondInA == "file (2).txt")
    names.release(root)
    names.release(inA, "A")
    names.release(inB, "B")
    names.release(secondInA, "A")
    println("PASS: safe relative folder paths, Arabic names, depth quotas, scoped reservations")
}
