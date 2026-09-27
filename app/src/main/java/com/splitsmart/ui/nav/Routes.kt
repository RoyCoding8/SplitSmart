package com.splitsmart.ui.nav

import kotlinx.serialization.Serializable

@Serializable object Home

@Serializable data class GroupDetail(val groupId: Long)

@Serializable data class FriendDetail(val groupId: Long)

@Serializable data class ExpenseDetail(val groupId: Long, val expenseId: Long)

@Serializable data class AddEditGroup(val groupId: Long? = null)

@Serializable data class AddEditExpense(val groupId: Long, val expenseId: Long? = null)

@Serializable data class History(val groupId: Long)

@Serializable data class Charts(val groupId: Long)

@Serializable data class Recurring(val groupId: Long)

@Serializable object Settings

@Serializable object Stats

@Serializable object Search
