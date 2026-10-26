package com.netkit.local.share

fun main() {
    val registry = ShareHandleRegistry<String>(4)
    registry.reset("root-data")
    check(registry.size == 1 && registry.find("root") == "root-data")
    val a = registry.register("file-a", "A")
    val b = registry.register("file-b", "B")
    val c = registry.register("file-c", "C")
    check(registry.size == 4)
    check(registry.register("file-a", "A-new") == a)
    check(registry.find(a) == "A-new")
    val d = registry.register("file-d", "D")
    check(registry.size == 4)
    check(registry.find("root") == "root-data")
    check(registry.find(b) == null) { "Oldest unused handle must be evicted" }
    check(registry.find(a) == "A-new" && registry.find(c) == "C" && registry.find(d) == "D")
    val replacement = registry.register("file-b", "B-new")
    check(replacement != b && registry.find(b) == null) { "Evicted IDs must never revive" }
    registry.reset(null)
    check(registry.size == 0 && registry.find("root") == null)
    check(registry.find(replacement) == null) { "Stopping a share revokes every handle" }
    check(runCatching { ShareHandleRegistry<String>(1) }.isFailure)
    println("PASS: bounded SAF handle registry (LRU, root, stable IDs, revocation)")
}
