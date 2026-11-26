#!/usr/bin/env kotlin
import java.io.File
import kotlin.system.exitProcess

/** sed '/pattern/a text' —— 在匹配到的每一行之后插入若干新行 */
fun File.insertAfter(anchor: Regex, vararg newLines: String) {
    val out = mutableListOf<String>()
    for (line in readLines()) {
        out.add(line)
        if (anchor.containsMatchIn(line)) out.addAll(newLines)
    }
    writeText(out.joinToString("\n") + "\n")
}

fun File.insertAfterFirst(anchor: Regex, vararg newLines: String) {
    val out = mutableListOf<String>()
    var inserted = false
    for (line in readLines()) {
        out.add(line)
        if (!inserted && anchor.containsMatchIn(line)) {
            out.addAll(newLines)
            inserted = true
        }
    }
    writeText(out.joinToString("\n") + "\n")
}

/** sed '/pattern/i text' —— 在匹配到的每一行之前插入若干新行 */
fun File.insertBefore(anchor: Regex, vararg newLines: String) {
    val out = mutableListOf<String>()
    for (line in readLines()) {
        if (anchor.containsMatchIn(line)) out.addAll(newLines)
        out.add(line)
    }
    writeText(out.joinToString("\n") + "\n")
}

fun File.insertBeforeFirst(anchor: Regex, vararg newLines: String) {
    val out = mutableListOf<String>()
    var inserted = false
    for (line in readLines()) {
        if (!inserted && anchor.containsMatchIn(line)) {
            out.addAll(newLines)
            inserted = true
        }
        out.add(line)
    }
    writeText(out.joinToString("\n") + "\n")
}

/** sed '/pattern/d' —— 删除匹配到的行 */
fun File.deleteLine(pattern: Regex) {
    val out = readLines().filterNot { pattern.containsMatchIn(it) }
    writeText(out.joinToString("\n") + "\n")
}

/** sed '/pattern/,+N d' —— 删除匹配行及其后 N 行 */
fun File.deleteLineAndFollowing(pattern: Regex, extraLines: Int) {
    val out = mutableListOf<String>()
    var skip = 0
    for (line in readLines()) {
        if (skip > 0) {
            skip--
            continue
        }
        if (pattern.containsMatchIn(line)) {
            skip = extraLines
            continue
        }
        out.add(line)
    }
    writeText(out.joinToString("\n") + "\n")
}

/** sed '/start/,/end/d' —— 删除从 start 到 end(含首尾)的整段,支持多段 */
fun File.deleteBlock(start: Regex, end: Regex) {
    val out = mutableListOf<String>()
    var inBlock = false
    for (line in readLines()) {
        if (!inBlock && start.containsMatchIn(line)) {
            inBlock = true
            continue
        }
        if (inBlock) {
            if (end.containsMatchIn(line)) inBlock = false
            continue
        }
        out.add(line)
    }
    writeText(out.joinToString("\n") + "\n")
}

/** perl -pe 's/pattern/repl/' —— 逐行做正则替换(支持 pattern 内的反向引用 \1) */
fun File.replaceEachLine(pattern: Regex, replacement: String) {
    val out = readLines().map { pattern.replace(it, replacement) }
    writeText(out.joinToString("\n") + "\n")
}

/** sed 's/exact_old/exact_new/' —— 整行精确替换 */
fun File.replaceWholeLine(oldLine: String, newLine: String) {
    val out = readLines().map { if (it == oldLine) newLine else it }
    writeText(out.joinToString("\n") + "\n")
}

val kmi = System.getenv("KMI") ?: ""
val sublevel = (System.getenv("SUBLEVEL") ?: "0").toIntOrNull() ?: 0
val mode = args.getOrNull(0) ?: "apply" // apply | postfix | revert
val workDir = args.getOrNull(1) ?: "kernel_workspace/kernel_platform/common"

fun f(relPath: String) = File(workDir, relPath)
fun File.rel(): String = this.path.removePrefix("$workDir/").removePrefix("$workDir\\")
val useColor = System.getenv("NO_COLOR") == null
fun tag(name: String, code: String) =
    if (useColor) "\u001B[${code}m[$name]\u001B[0m" else "[$name]"
fun logApply(file: File, detail: String) = println("${tag("apply", "31")}-${file.rel()}: $detail")          // 红
fun logPostfix(file: File, detail: String) = println("${tag("postfix", "38;5;208")}-${file.rel()}: $detail") // 橙
fun logRevert(file: File, detail: String) = println("${tag("revert", "33")}-${file.rel()}: $detail")         // 黄

val fdinfoCommentStart = Regex("""^[ \t]*/\*$""")
val fdinfoCommentEnd = Regex("""^[ \t]*u32 mask = mark->mask & IN_ALL_EVENTS;$""")

val inotifyFdinfoFuncAnchor = Regex("""^static void inotify_fdinfo\(struct seq_file \*m, struct fsnotify_mark \*mark\)$""")

fun addInotifyMarkUserMaskFunction(file: File) {
    file.insertBeforeFirst(
        inotifyFdinfoFuncAnchor,
        "static inline u32 inotify_mark_user_mask(struct fsnotify_mark *mark)",
        "{",
        "\treturn mark->mask & IN_ALL_EVENTS;",
        "}",
        ""
    )
}

fun applyFdinfo(file: File) {
    file.deleteBlock(fdinfoCommentStart, fdinfoCommentEnd)
    logApply(file, "removed inline mask-computation comment block ending at 'u32 mask = mark->mask & IN_ALL_EVENTS;' inside inotify_fdinfo()")
    file.replaceEachLine(Regex("""\bmask,\s*mark->ignored_mask"""), "inotify_mark_user_mask(mark)")
    logApply(file, "replaced seq_printf argument 'mask, mark->ignored_mask' with inotify_mark_user_mask(mark) call")
    file.replaceEachLine(Regex("""ignored_mask:%x"""), "ignored_mask:0")
    logApply(file, "replaced format specifier 'ignored_mask:%x' with literal 'ignored_mask:0'")
    addInotifyMarkUserMaskFunction(file)
    logApply(file, "inserted new static inline inotify_mark_user_mask() helper function before inotify_fdinfo()")
}

// android15-6.6 SUBLEVEL<=30 特殊单独处理

fun applyAndroid15VmaBlock(taskMmu: File, namespace: File) {
    // 1) 在含 smap_gather_stats(vma, &mss, last_vma_end); 的行之后插入新语句
    taskMmu.insertAfter(
        Regex("""smap_gather_stats\(vma, &mss, last_vma_end\);"""),
        "last_vma_end = vma->vm_end;"
    )
    logApply(taskMmu, "inserted 'last_vma_end = vma->vm_end;' immediately after the smap_gather_stats(vma, &mss, last_vma_end); call")

    // 2) 从文件末尾往前找最后一处 "last_vma_end = vma->vm_end;"，
    //    给它加缩进并在其后补一个 "}"，再往前找最近的
    //    "if (vma->vm_end > last_vma_end)" 把行尾的 ")" 换成 ") {"
    val lines = taskMmu.readLines().toMutableList()
    val ifPattern = Regex("""if\s*\(vma->vm_end > last_vma_end\)""")
    val trailingParen = Regex("""\)\s*$""")

    for (i in lines.indices.reversed()) {
        if (lines[i].contains("last_vma_end = vma->vm_end;")) {
            lines[i] = "\t\t\t\t" + lines[i]
            lines.add(i + 1, "\t\t\t}")
            for (j in i downTo 0) {
                if (ifPattern.containsMatchIn(lines[j])) {
                    lines[j] = trailingParen.replace(lines[j], ") {")
                    break
                }
            }
            break
        }
    }
    taskMmu.writeText(lines.joinToString("\n") + "\n")
    logApply(taskMmu, "opened a new '{ ... }' block by appending '{' to the trailing 'if (vma->vm_end > last_vma_end)' and inserting a closing '}' after the last 'last_vma_end = vma->vm_end;' statement, re-indenting that statement one level deeper")

    // 3) task_mmu.c: 在 "int ret = 0, copied = 0;" 之后插入两行
    taskMmu.insertAfter(
        Regex("""int ret = 0, copied = 0;"""),
        "\tunsigned int nr_subpages = __PAGE_SIZE / PAGE_SIZE;",
        "\tpagemap_entry_t *res = NULL;"
    )
    logApply(taskMmu, "declared 'unsigned int nr_subpages = __PAGE_SIZE / PAGE_SIZE;' and 'pagemap_entry_t *res = NULL;' right after 'int ret = 0, copied = 0;'")
}

fun revertAndroid15VmaBlock(taskMmu: File, namespace: File) {
    namespace.deleteLine(Regex("""#include <trace/hooks/fs\.h>"""))
    logRevert(namespace, "removed #include <trace/hooks/fs.h>")
    taskMmu.deleteLine(Regex("""unsigned int nr_subpages = __PAGE_SIZE / PAGE_SIZE;"""))
    logRevert(taskMmu, "removed 'unsigned int nr_subpages = __PAGE_SIZE / PAGE_SIZE;' declaration")
    taskMmu.deleteLine(Regex("""pagemap_entry_t \*res = NULL;"""))
    logRevert(taskMmu, "removed 'pagemap_entry_t *res = NULL;' declaration")
}

/** 兜底修复 */
fun applyPostPatchFixups() {
    val taskMmu = f("fs/proc/task_mmu.c")
    if (taskMmu.exists()) {
        val content = taskMmu.readText()
        if (content.contains("VMA_PAD_START(") &&
            !Regex("""#include <linux/pgsize_migration(_inline)?\.h>|define VMA_PAD_START""").containsMatchIn(content)
        ) {
            val lines = taskMmu.readLines().toMutableList()
            lines.addAll(
                1,
                listOf(
                    "#ifndef VMA_PAD_START",
                    "#define VMA_PAD_START(vma) ((vma)->vm_end)",
                    "#endif"
                )
            )
            taskMmu.writeText(lines.joinToString("\n") + "\n")
            logPostfix(taskMmu, "inserted fallback macro '#define VMA_PAD_START(vma) ((vma)->vm_end)' guarded by #ifndef, because VMA_PAD_START() is used but neither pgsize_migration(_inline).h is included nor the macro is defined on this baseline")
        }

        val content2 = taskMmu.readText()
        if (content2.contains("__fold_filemap_fixup_entry(") &&
            !Regex("""static\s+inline\s+void\s+__fold_filemap_fixup_entry""").containsMatchIn(content2)
        ) {
            val headerFile = f("include/linux/page_size_compat.h")
            val headerDeclaresFn = headerFile.exists() && headerFile.readText().contains("__fold_filemap_fixup_entry")

            if (headerDeclaresFn) {
                if (!content2.contains("#include <linux/page_size_compat.h>")) {
                    val lines = taskMmu.readLines().toMutableList()
                    lines.add(1, "#include <linux/page_size_compat.h>")
                    taskMmu.writeText(lines.joinToString("\n") + "\n")
                    logPostfix(taskMmu, "added #include <linux/page_size_compat.h>, since that header already declares __fold_filemap_fixup_entry() and it was only being called, not defined, in this file")
                }
            } else {
                val lines = taskMmu.readLines().toMutableList()
                val lastIncludeIdx = lines.indexOfLast { it.trimStart().startsWith("#include") }
                val insertAt = if (lastIncludeIdx >= 0) lastIncludeIdx + 1 else 1
                lines.addAll(
                    insertAt,
                    listOf(
                        "#ifndef __fold_filemap_fixup_entry",
                        "static inline void __fold_filemap_fixup_entry(struct vma_iterator *iter, unsigned long *end) { }",
                        "#endif /* __fold_filemap_fixup_entry */"
                    )
                )
                taskMmu.writeText(lines.joinToString("\n") + "\n")
                logPostfix(taskMmu, "inserted a no-op static inline stub 'void __fold_filemap_fixup_entry(struct vma_iterator *iter, unsigned long *end) { }' after the last #include, because page_size_compat.h is missing or doesn't declare this symbol on this baseline")
            }
        }
    }

    if (kmi == "android12-5.10" || kmi == "android13-5.10") {
        val namei = f("fs/namei.c")
        if (namei.exists() && namei.readText().contains("set_nameidata(nd, old_dfd, fake_filename, NULL)")) {
            namei.replaceEachLine(
                Regex("""set_nameidata\(nd, old_dfd, fake_filename, NULL\)"""),
                "set_nameidata(nd, old_dfd, fake_filename)"
            )
            logPostfix(namei, "rewrote 4-arg set_nameidata(nd, old_dfd, fake_filename, NULL) calls to the 3-arg form set_nameidata(nd, old_dfd, fake_filename) required on this 5.10 baseline")
        }
    }

    if (kmi == "android16-6.12") {
        val openC = f("fs/open.c")
        if (openC.exists() && openC.readText().contains("getname_flags(filename, lookup_flags, NULL)")) {
            openC.replaceEachLine(
                Regex("""getname_flags\(filename, lookup_flags, NULL\)"""),
                "getname_flags(filename, lookup_flags)"
            )
            logPostfix(openC, "rewrote 3-arg getname_flags(filename, lookup_flags, NULL) calls to the 2-arg form getname_flags(filename, lookup_flags) required on this 6.12 baseline")
        }
    }

    fixSusfsSusTypeCollision()
    normalizeBuildMetadata()
    extendKallsymsSymbolFilter()
}

fun normalizeBuildMetadata() {
    val mkcompile = f("scripts/mkcompile_h")
    if (!mkcompile.exists()) return

    val content = mkcompile.readText().replace("\r\n", "\n")
    if (content.contains("KBUILD_BUILD_USER:-build-user")) return

    val lines = content.split("\n").toMutableList()
    if (lines.isNotEmpty()) {
        lines.addAll(
            1,
            listOf(
                "KBUILD_BUILD_USER=\${KBUILD_BUILD_USER:-build-user}",
                "KBUILD_BUILD_HOST=\${KBUILD_BUILD_HOST:-build-host}"
            )
        )
        mkcompile.writeText(lines.joinToString("\n"))
        logPostfix(mkcompile, "set default KBUILD_BUILD_USER=build-user and KBUILD_BUILD_HOST=build-host")
    }
}

fun extendKallsymsSymbolFilter() {
    val kallsyms = f("kernel/kallsyms.c")
    if (!kallsyms.exists()) return

    val content = kallsyms.readText().replace("\r\n", "\n")
    val marker = """susfs_starts_with(iter->name, "is_zygote"))"""
    if (!content.contains(marker) || content.contains("""susfs_starts_with(iter->name, "sukisu_")""")) return

    val extraPrefixes = listOf(
        "sukisu_",
        "do_kpm",
        "avc_spoof_",
        "selinux_hide_",
        "mount_hide_",
        "uts_spoof_",
        "anon_ksu_",
        "setup_ksu_cred",
        "is_task_ksu_domain",
        "throne_tracker_",
        "sulog_",
        "do_get_sulog_",
        "write_sulog",
        "send_sulog_",
        "kernel_adb_root_",
        "bbg_",
        "baseband_guard",
        "handle_selinux_",
        "handle_sel_write_"
    )

    val expanded = buildString {
        append("""susfs_starts_with(iter->name, "is_zygote") ||""")
        for ((idx, pfx) in extraPrefixes.withIndex()) {
            append("\n\t\t\tsusfs_starts_with(iter->name, \"")
            append(pfx)
            append("\")")
            append(if (idx == extraPrefixes.lastIndex) ")" else " ||")
        }
    }

    kallsyms.writeText(content.replace(marker, expanded))
    logPostfix(kallsyms, "extended s_show symbol filter prefixes")
}

fun replaceFirstMatch(input: String, regex: Regex, transform: (MatchResult) -> String): String {
    val m = regex.find(input) ?: return input
    return input.replaceRange(m.range, transform(m))
}

/**
 * Fix SUSFS selinux_hide SUS_TYPE_HLIST hash-bucket collision bug in fs/susfs.c:
 * st_susfs_sus_type_hlist lacked a type_value field, causing susfs_is_sus_type_rcu()
 * and susfs_add_sus_type() to match any SELinux type colliding in the 10-bit hash
 * table (e.g. permissioncontroller_app=1849 colliding with ksu_file=4433 in bucket 261).
 */
fun fixSusfsSusTypeCollision() {
    val susfs = f("fs/susfs.c")
    if (!susfs.exists()) return

    val normalized = susfs.readText().replace("\r\n", "\n")
    if (!normalized.contains("SUS_TYPE_HLIST")) return
    if (Regex("""struct\s+st_susfs_sus_type_hlist\s*\{[^}]*type_value\s*;""").containsMatchIn(normalized)) {
        logPostfix(susfs, "SUS_TYPE_HLIST already stores/checks type_value, skipping collision fix")
        return
    }

    val oldBlock = listOf(
        "struct st_susfs_sus_type_hlist {",
        "\tstruct hlist_node                       node;",
        "};",
        "",
        "int susfs_add_sus_type(const char *secctx, u32 type_value) {",
        "\tstruct st_susfs_sus_type_hlist *new_entry, *tmp_entry;",
        "\tstruct hlist_node *tmp_hlist_node;",
        "\tint err = 0;",
        "",
        "\tmutex_lock(&susfs_mutex_lock_sus_type);",
        "\thash_for_each_possible_safe(SUS_TYPE_HLIST, tmp_entry, tmp_hlist_node, node, type_value) {",
        "\t\tSUSFS_LOGE(\"secctx '%s', type_value '%u', was added before already\\n\",",
        "\t\t\tsecctx, type_value);",
        "\t\terr = -EINVAL;",
        "\t\tgoto unlock;",
        "\t}",
        "",
        "\tnew_entry = kzalloc(sizeof(struct st_susfs_sus_type_hlist), GFP_KERNEL);",
        "\tif (!new_entry) {",
        "\t\terr = -ENOMEM;",
        "\t\tgoto unlock;",
        "\t}",
        "",
        "\thash_add_rcu(SUS_TYPE_HLIST, &new_entry->node, type_value);",
        "\tSUSFS_LOGI_RL(\"secctx '%s', type '%u', has been added to SUS_TYPE_HLIST\\n\",",
        "\t\t\t\t\tsecctx, type_value);",
        "unlock:",
        "\tmutex_unlock(&susfs_mutex_lock_sus_type);",
        "\treturn err;",
        "}",
        "",
        "bool susfs_is_sus_type_rcu(u32 type_value) {",
        "\tstruct st_susfs_sus_type_hlist *entry = NULL;",
        "",
        "\thash_for_each_possible_rcu(SUS_TYPE_HLIST, entry, node, type_value) {",
        "\t\treturn true;",
        "\t}",
        "\treturn false;",
        "}"
    ).joinToString("\n")

    val newBlock = listOf(
        "struct st_susfs_sus_type_hlist {",
        "\tu32                                     type_value;",
        "\tstruct hlist_node                       node;",
        "};",
        "",
        "int susfs_add_sus_type(const char *secctx, u32 type_value) {",
        "\tstruct st_susfs_sus_type_hlist *new_entry, *tmp_entry;",
        "\tstruct hlist_node *tmp_hlist_node;",
        "\tint err = 0;",
        "",
        "\tmutex_lock(&susfs_mutex_lock_sus_type);",
        "\thash_for_each_possible_safe(SUS_TYPE_HLIST, tmp_entry, tmp_hlist_node, node, type_value) {",
        "\t\tif (tmp_entry->type_value == type_value) {",
        "\t\t\tSUSFS_LOGE(\"secctx '%s', type_value '%u', was added before already\\n\",",
        "\t\t\t\tsecctx, type_value);",
        "\t\t\terr = -EINVAL;",
        "\t\t\tgoto unlock;",
        "\t\t}",
        "\t}",
        "",
        "\tnew_entry = kzalloc(sizeof(struct st_susfs_sus_type_hlist), GFP_KERNEL);",
        "\tif (!new_entry) {",
        "\t\terr = -ENOMEM;",
        "\t\tgoto unlock;",
        "\t}",
        "",
        "\tnew_entry->type_value = type_value;",
        "\thash_add_rcu(SUS_TYPE_HLIST, &new_entry->node, type_value);",
        "\tSUSFS_LOGI_RL(\"secctx '%s', type '%u', has been added to SUS_TYPE_HLIST\\n\",",
        "\t\t\t\t\tsecctx, type_value);",
        "unlock:",
        "\tmutex_unlock(&susfs_mutex_lock_sus_type);",
        "\treturn err;",
        "}",
        "",
        "bool susfs_is_sus_type_rcu(u32 type_value) {",
        "\tstruct st_susfs_sus_type_hlist *entry = NULL;",
        "",
        "\thash_for_each_possible_rcu(SUS_TYPE_HLIST, entry, node, type_value) {",
        "\t\tif (entry->type_value == type_value)",
        "\t\t\treturn true;",
        "\t}",
        "\treturn false;",
        "}"
    ).joinToString("\n")

    if (normalized.contains(oldBlock)) {
        susfs.writeText(normalized.replace(oldBlock, newBlock))
        logPostfix(susfs, "fixed SUS_TYPE_HLIST hash-collision bug: stored u32 type_value in st_susfs_sus_type_hlist and verified exact type_value equality in susfs_add_sus_type()/susfs_is_sus_type_rcu()")
        return
    }

    // Fallback regex replacement in case upstream changes minor formatting in SUS_TYPE_HLIST
    var updated = normalized
    val r1 = Regex("""(struct st_susfs_sus_type_hlist \{\n)([ \t]*struct hlist_node)""")
    val r2 = Regex("""(hash_for_each_possible_safe\(SUS_TYPE_HLIST,\s*tmp_entry,\s*tmp_hlist_node,\s*node,\s*type_value\)\s*\{\n)([ \t]*SUSFS_LOGE\([^;]+;\n[ \t]*err = -EINVAL;\n[ \t]*goto unlock;\n)([ \t]*\})""")
    val r3 = Regex("""([ \t]*)(hash_add_rcu\(SUS_TYPE_HLIST,\s*&new_entry->node,\s*type_value\);)""")
    val r4 = Regex("""(hash_for_each_possible_rcu\(SUS_TYPE_HLIST,\s*entry,\s*node,\s*type_value\)\s*\{\n)([ \t]*)(return true;)""")
    if (r1.containsMatchIn(updated) && r2.containsMatchIn(updated) && r3.containsMatchIn(updated) && r4.containsMatchIn(updated)) {
        updated = replaceFirstMatch(updated, r1) { m -> "${m.groupValues[1]}\tu32                                     type_value;\n${m.groupValues[2]}" }
        updated = replaceFirstMatch(updated, r2) { m -> "${m.groupValues[1]}\t\tif (tmp_entry->type_value == type_value) {\n${m.groupValues[2]}\t\t}\n${m.groupValues[3]}" }
        updated = replaceFirstMatch(updated, r3) { m -> "${m.groupValues[1]}new_entry->type_value = type_value;\n${m.groupValues[1]}${m.groupValues[2]}" }
        updated = replaceFirstMatch(updated, r4) { m -> "${m.groupValues[1]}${m.groupValues[2]}if (entry->type_value == type_value)\n${m.groupValues[2]}\t${m.groupValues[3]}" }
        susfs.writeText(updated)
        logPostfix(susfs, "fixed SUS_TYPE_HLIST hash-collision bug via regex fallback")
    } else {
        System.err.println("[postfix]-fs/susfs.c: WARNING: SUS_TYPE_HLIST pattern did not match upstream susfs.c")
    }
}

// Main
fun apply() {
    if (kmi == "android12-5.10") {
        if (sublevel <= 43) {
            val file = f("fs/proc/base.c")
            file.replaceEachLine(
                Regex("""(int|size_t)\s+this_len\s*=\s*min_t\s*\(\s*\1\s*,"""),
                "size_t this_len = min_t(size_t,"
            )
            logApply(file, "normalized 'this_len' declaration and its min_t() call to use size_t (was declared/cast as int on this baseline)")
        }
        if (sublevel <= 117) {
            applyFdinfo(f("fs/notify/fdinfo.c"))
        }
    }

    if (kmi == "android13-5.10") {
        if (sublevel <= 107) {
            applyFdinfo(f("fs/notify/fdinfo.c"))
        }
    }

    if (kmi == "android13-5.15") {
        if (sublevel <= 41) {
            val namespace = f("fs/namespace.c")
            namespace.insertAfter(
                Regex("""^#include <linux/shmem_fs\.h>$"""),
                "#include <linux/mnt_idmapping.h>"
            )
            logApply(namespace, "added #include <linux/mnt_idmapping.h> directly after #include <linux/shmem_fs.h>")

            val openC = f("fs/open.c")
            openC.insertAfter(
                Regex("""^#include <linux/compat\.h>$"""),
                "#include <linux/mnt_idmapping.h>"
            )
            logApply(openC, "added #include <linux/mnt_idmapping.h> directly after #include <linux/compat.h>")

            val idmap = f("include/linux/mnt_idmapping.h")
            if (!idmap.exists()) {
                idmap.writeText("/* Stub: mnt idmap helpers live in linux/fs.h on this sublevel. */\n")
                logApply(idmap, "stubbed missing include/linux/mnt_idmapping.h")
            }

            applyFdinfo(f("fs/notify/fdinfo.c"))
        }
        if (sublevel >= 123) {
            val memory = f("mm/memory.c")
            memory.deleteLine(Regex("""#include <linux/swap_slots\.h>"""))
            logApply(memory, "removed #include <linux/swap_slots.h> (now provided elsewhere on this baseline)")
        }
    }

    if (kmi == "android14-6.1") {
        if (sublevel <= 25) {
            val base = f("fs/proc/base.c")
            base.insertAfter(
                Regex("""^#include <trace/events/oom\.h>$"""),
                "#include <trace/hooks/sched.h>"
            )
            logApply(base, "added #include <trace/hooks/sched.h> directly after #include <trace/events/oom.h>")
        }
        if (sublevel <= 141) {
            val base = f("fs/proc/base.c")
            base.insertAfter(
                Regex("""^#include <linux/cpufreq_times\.h>$"""),
                "#include <linux/dma-buf.h>"
            )
            logApply(base, "added #include <linux/dma-buf.h> directly after #include <linux/cpufreq_times.h>")
        }
    }

    if (kmi == "android15-6.6") {
        if (sublevel <= 30) {
            applyAndroid15VmaBlock(f("fs/proc/task_mmu.c"), f("fs/namespace.c"))
        }
        if (sublevel <= 57) {
            val memory = f("mm/memory.c")
            memory.insertAfter(
                Regex("""^#include <linux/sched/sysctl\.h>$"""),
                "#include <linux/zswap.h>"
            )
            logApply(memory, "added #include <linux/zswap.h> directly after #include <linux/sched/sysctl.h>")
        }
        if (sublevel <= 92) {
            val base = f("fs/proc/base.c")
            base.insertAfter(
                Regex("""^#include <linux/cpufreq_times\.h>$"""),
                "#include <linux/dma-buf.h>"
            )
            logApply(base, "added #include <linux/dma-buf.h> directly after #include <linux/cpufreq_times.h>")
        }
    }

    if (kmi == "android16-6.12") {
        if (sublevel >= 58) {
            val exec = f("fs/exec.c")
            exec.deleteLine(Regex("""^#include <linux/dma-buf\.h>$"""))
            logApply(exec, "removed #include <linux/dma-buf.h>")
        }
        if (sublevel >= 69) {
            val taskMmu = f("fs/proc/task_mmu.c")
            taskMmu.replaceEachLine(Regex("""vma_data_pages"""), "vma_pages")
            logApply(taskMmu, "renamed all occurrences of vma_data_pages to vma_pages (upstream helper rename on this baseline)")
        }
    }

}

fun postfix() {
    applyPostPatchFixups()
}

fun revert() {
    if (kmi == "android12-5.10") {
        if (sublevel <= 43) {
            val file = f("fs/proc/base.c")
            file.replaceWholeLine(
                "size_t this_len = min_t(size_t, count, PAGE_SIZE);",
                "int this_len = min_t(int, count, PAGE_SIZE);"
            )
            logRevert(file, "restored 'int this_len = min_t(int, count, PAGE_SIZE);' declaration, undoing the size_t normalization")
        }
    }

    if (kmi == "android13-5.15") {
        if (sublevel <= 41) {
            val namespace = f("fs/namespace.c")
            namespace.deleteLine(Regex("""#include <linux/mnt_idmapping\.h>$"""))
            logRevert(namespace, "removed #include <linux/mnt_idmapping.h>")

            val openC = f("fs/open.c")
            openC.deleteLine(Regex("""#include <linux/mnt_idmapping\.h>$"""))
            logRevert(openC, "removed #include <linux/mnt_idmapping.h>")

            val susfs = f("fs/susfs.c")
            susfs.replaceEachLine(
                Regex(Regex.escape("i_uid_into_mnt(i_user_ns(&fi->inode), &fi->inode).val")),
                "i_uid_into_mnt(&init_user_ns, &fi->inode).val"
            )
            logRevert(susfs, "reverted i_uid_into_mnt(i_user_ns(&fi->inode), &fi->inode).val back to i_uid_into_mnt(&init_user_ns, &fi->inode).val")
            susfs.replaceEachLine(
                Regex(Regex.escape("i_uid_into_mnt(i_user_ns(inode), inode).val")),
                "i_uid_into_mnt(&init_user_ns, inode).val"
            )
            logRevert(susfs, "reverted i_uid_into_mnt(i_user_ns(inode), inode).val back to i_uid_into_mnt(&init_user_ns, inode).val")
        }
        if (sublevel >= 123) {
            val memory = f("mm/memory.c")
            memory.insertBefore(
                Regex("""#ifdef CONFIG_KSU_SUSFS_SUS_MAP"""),
                "#include <linux/swap_slots.h>"
            )
            logRevert(memory, "restored #include <linux/swap_slots.h> directly before #ifdef CONFIG_KSU_SUSFS_SUS_MAP")
        }
    }

    if (kmi == "android14-6.1") {
        if (sublevel <= 25) {
            val base = f("fs/proc/base.c")
            base.deleteLine(Regex("""^#include <trace/hooks/sched\.h>$"""))
            logRevert(base, "removed #include <trace/hooks/sched.h>")
        }
        if (sublevel <= 141) {
            val base = f("fs/proc/base.c")
            base.deleteLine(Regex("""^#include <linux/dma-buf\.h>$"""))
            logRevert(base, "removed #include <linux/dma-buf.h>")
        }
    }

    if (kmi == "android15-6.6") {
        if (sublevel <= 30) {
            revertAndroid15VmaBlock(f("fs/proc/task_mmu.c"), f("fs/namespace.c"))
        }
        if (sublevel <= 57) {
            val memory = f("mm/memory.c")
            memory.deleteLine(Regex("""^#include <linux/zswap\.h>$"""))
            logRevert(memory, "removed #include <linux/zswap.h>")
        }
        if (sublevel <= 92) {
            val base = f("fs/proc/base.c")
            base.deleteLine(Regex("""^#include <linux/dma-buf\.h>$"""))
            logRevert(base, "removed #include <linux/dma-buf.h>")
        }
    }

    if (kmi == "android16-6.12") {
        if (sublevel >= 58) {
            val exec = f("fs/exec.c")
            exec.insertAfterFirst(Regex("""^#include """), "#include <linux/dma-buf.h>")
            logRevert(exec, "restored #include <linux/dma-buf.h> directly after the first #include line")
        }
        if (sublevel >= 69) {
            val taskMmu = f("fs/proc/task_mmu.c")
            taskMmu.replaceEachLine(Regex("""vma_pages"""), "vma_data_pages")
            logRevert(taskMmu, "renamed all occurrences of vma_pages back to vma_data_pages")
        }
    }
}

when (mode) {
    "apply" -> apply()
    "postfix" -> postfix()
    "revert" -> revert()
    else -> {
        println("Usage: kotlin SusfsCompat.kts <apply|postfix|revert> [workDir]")
        exitProcess(1)
    }
}