package com.splitsmart.data

import java.io.File

/**
 * Every stored image lives in one subdirectory named by kind, created on demand. The file name
 * starts with a per-kind letter and carries a unique stamp, because two files written in the same
 * nanosecond for the same row would otherwise collide.
 */
private fun mediaFile(base: File, kind: String, prefix: String): File =
    File(File(base, kind).also { it.mkdirs() }, "${prefix}_${System.nanoTime()}.jpg")

fun receiptFile(base: File, groupId: Long, expenseId: Long): File =
    mediaFile(base, "receipts", "r${groupId}_${expenseId}")

/** Written before the expense row that will own it exists, so there is no expense id to name it by. */
fun restoredReceiptFile(base: File, groupId: Long): File =
    mediaFile(base, "receipts", "r${groupId}_restore")

fun avatarFile(base: File, groupId: Long, memberId: Long): File =
    mediaFile(base, "avatars", "a${groupId}_${memberId}")

/**
 * A staging path for a photo picked before the member it belongs to exists. The member has no id
 * yet, so naming it by group alone would let a second member overwrite the first member's photo.
 */
fun pendingAvatarFile(base: File, groupId: Long): File =
    mediaFile(base, "avatars", "p${groupId}")
