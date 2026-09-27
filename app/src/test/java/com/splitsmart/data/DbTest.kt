package com.splitsmart.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider

// An in-memory database and a repository over it, torn down after each test. Subclass it and
// use [db], [repo] and [now] directly; override [openDb] only to point the fixture somewhere
// other than an in-memory database.
abstract class DbTest {
  protected lateinit var db: AppDatabase
  protected lateinit var repo: SplitRepository
  protected val now = 1_700_000_000_000L

  protected open fun openDb(): AppDatabase =
      Room.inMemoryDatabaseBuilder(
              ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
          .allowMainThreadQueries()
          .build()

  @org.junit.Before
  fun open() {
    db = openDb()
    repo = SplitRepository(db)
  }

  @org.junit.After
  fun close() {
    db.close()
  }
}
