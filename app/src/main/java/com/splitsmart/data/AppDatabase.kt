package com.splitsmart.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities =
        [
            Group::class,
            Member::class,
            Expense::class,
            ExpenseShare::class,
            ExpensePayment::class,
            ExpenseItem::class,
            Settlement::class,
            RecurringTemplate::class,
            Subgroup::class,
            SubgroupMember::class,
            Event::class,
            Comment::class,
            ExpenseFts::class,
            MemberFts::class,
            FxRate::class,
            CustomCategory::class],
    version = 12,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
  abstract fun groups(): GroupDao

  abstract fun members(): MemberDao

  abstract fun expenses(): ExpenseDao

  abstract fun subgroups(): SubgroupDao

  abstract fun events(): EventDao

  abstract fun comments(): CommentDao

  abstract fun search(): SearchDao

  abstract fun rates(): FxDao

  abstract fun customCats(): CustomCatDao
}

val MIGRATION_11_12 =
    object : Migration(11, 12) {
      override fun migrate(db: SupportSQLiteDatabase) {
        // NOT NULL with a zero default, the true value for every expense already stored:
        // none recorded a tip or tax, and their totals are the subtotals.
        db.execSQL("ALTER TABLE `expenses` ADD COLUMN `tipMinor` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `expenses` ADD COLUMN `taxMinor` INTEGER NOT NULL DEFAULT 0")
      }
    }

val MIGRATION_10_11 =
    object : Migration(10, 11) {
      override fun migrate(db: SupportSQLiteDatabase) {
        // NOT NULL with a default, so every expense already stored is readable without
        // rewriting the table: none recorded how it was split, and EQUAL is the only value
        // true of all of them.
        db.execSQL("ALTER TABLE `expenses` ADD COLUMN `splitType` TEXT NOT NULL DEFAULT 'EQUAL'")
      }
    }

val MIGRATION_9_10 =
    object : Migration(9, 10) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_recurringId` ON `expenses` (`recurringId`)")
      }
    }

val MIGRATION_8_9 =
    object : Migration(8, 9) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_subgroup_members_memberId` ON `subgroup_members` (`memberId`)")
      }
    }

val MIGRATION_7_8 =
    object : Migration(7, 8) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `custom_categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `emoji` TEXT NOT NULL)")
        db.execSQL("ALTER TABLE `groups` ADD COLUMN `color` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `members` ADD COLUMN `avatarPath` TEXT")
        db.execSQL("ALTER TABLE `expenses` ADD COLUMN `customCatId` INTEGER")
      }
    }

val MIGRATION_6_7 =
    object : Migration(6, 7) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `fx_rates` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `groupId` INTEGER NOT NULL, `fromCode` TEXT NOT NULL, `toCode` TEXT NOT NULL, `rate` REAL NOT NULL, `timeEpoch` INTEGER NOT NULL, FOREIGN KEY(`groupId`) REFERENCES `groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_fx_rates_groupId` ON `fx_rates` (`groupId`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_fx_rates_groupId_fromCode_toCode_timeEpoch` ON `fx_rates` (`groupId`, `fromCode`, `toCode`, `timeEpoch`)")
      }
    }

val MIGRATION_5_6 =
    object : Migration(5, 6) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `members` ADD COLUMN `settleNudge` INTEGER NOT NULL DEFAULT 0")
      }
    }

val MIGRATION_4_5 =
    object : Migration(4, 5) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE VIRTUAL TABLE `expense_fts` USING FTS4(`note` TEXT, `category` TEXT NOT NULL, content=`expenses`)")
        db.execSQL(
            "CREATE TRIGGER room_fts_content_sync_expense_fts_BEFORE_UPDATE BEFORE UPDATE ON `expenses` BEGIN DELETE FROM `expense_fts` WHERE `docid`=OLD.`rowid`; END")
        db.execSQL(
            "CREATE TRIGGER room_fts_content_sync_expense_fts_BEFORE_DELETE BEFORE DELETE ON `expenses` BEGIN DELETE FROM `expense_fts` WHERE `docid`=OLD.`rowid`; END")
        db.execSQL(
            "CREATE TRIGGER room_fts_content_sync_expense_fts_AFTER_UPDATE AFTER UPDATE ON `expenses` BEGIN INSERT INTO `expense_fts`(`docid`, `note`, `category`) VALUES (NEW.`rowid`, NEW.`note`, NEW.`category`); END")
        db.execSQL(
            "CREATE TRIGGER room_fts_content_sync_expense_fts_AFTER_INSERT AFTER INSERT ON `expenses` BEGIN INSERT INTO `expense_fts`(`docid`, `note`, `category`) VALUES (NEW.`rowid`, NEW.`note`, NEW.`category`); END")
        db.execSQL(
            "CREATE VIRTUAL TABLE `member_fts` USING FTS4(`name` TEXT NOT NULL, content=`members`)")
        db.execSQL(
            "CREATE TRIGGER room_fts_content_sync_member_fts_BEFORE_UPDATE BEFORE UPDATE ON `members` BEGIN DELETE FROM `member_fts` WHERE `docid`=OLD.`rowid`; END")
        db.execSQL(
            "CREATE TRIGGER room_fts_content_sync_member_fts_BEFORE_DELETE BEFORE DELETE ON `members` BEGIN DELETE FROM `member_fts` WHERE `docid`=OLD.`rowid`; END")
        db.execSQL(
            "CREATE TRIGGER room_fts_content_sync_member_fts_AFTER_UPDATE AFTER UPDATE ON `members` BEGIN INSERT INTO `member_fts`(`docid`, `name`) VALUES (NEW.`rowid`, NEW.`name`); END")
        db.execSQL(
            "CREATE TRIGGER room_fts_content_sync_member_fts_AFTER_INSERT AFTER INSERT ON `members` BEGIN INSERT INTO `member_fts`(`docid`, `name`) VALUES (NEW.`rowid`, NEW.`name`); END")
        db.execSQL("INSERT INTO `expense_fts`(`expense_fts`) VALUES ('rebuild')")
        db.execSQL("INSERT INTO `member_fts`(`member_fts`) VALUES ('rebuild')")
      }
    }

val MIGRATION_3_4 =
    object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `comments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `expenseId` INTEGER NOT NULL, `authorId` INTEGER NOT NULL, `text` TEXT NOT NULL, `timeEpoch` INTEGER NOT NULL, FOREIGN KEY(`expenseId`) REFERENCES `expenses`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_comments_expenseId` ON `comments` (`expenseId`)")
      }
    }

val MIGRATION_2_3 =
    object : Migration(2, 3) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `groupId` INTEGER NOT NULL, `timeEpoch` INTEGER NOT NULL, `kind` TEXT NOT NULL, `summary` TEXT NOT NULL, FOREIGN KEY(`groupId`) REFERENCES `groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_groupId` ON `events` (`groupId`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_events_timeEpoch_id` ON `events` (`timeEpoch`, `id`)")
      }
    }

val MIGRATION_1_2 =
    object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `groups` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'GROUP'")
        db.execSQL("ALTER TABLE `expenses` ADD COLUMN `receiptUri` TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `payments` (`expenseId` INTEGER NOT NULL, `memberId` INTEGER NOT NULL, `paidMinor` INTEGER NOT NULL, PRIMARY KEY(`expenseId`, `memberId`), FOREIGN KEY(`expenseId`) REFERENCES `expenses`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `expenseId` INTEGER NOT NULL, `label` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, FOREIGN KEY(`expenseId`) REFERENCES `expenses`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `subgroups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `groupId` INTEGER NOT NULL, `name` TEXT NOT NULL, FOREIGN KEY(`groupId`) REFERENCES `groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `subgroup_members` (`subgroupId` INTEGER NOT NULL, `memberId` INTEGER NOT NULL, PRIMARY KEY(`subgroupId`, `memberId`), FOREIGN KEY(`subgroupId`) REFERENCES `subgroups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`memberId`) REFERENCES `members`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_items_expenseId` ON `items` (`expenseId`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_subgroups_groupId` ON `subgroups` (`groupId`)")
      }
    }

class Converters {
  @TypeConverter fun cat2s(c: Category) = c.name

  @TypeConverter
  fun s2cat(s: String) = runCatching { Category.valueOf(s) }.getOrDefault(Category.OTHER)

  @TypeConverter fun freq2s(f: Frequency) = f.name

  @TypeConverter
  fun s2freq(s: String) = runCatching { Frequency.valueOf(s) }.getOrDefault(Frequency.MONTHLY)

  @TypeConverter fun kind2s(k: GroupKind) = k.name

  @TypeConverter
  fun s2kind(s: String) = runCatching { GroupKind.valueOf(s) }.getOrDefault(GroupKind.GROUP)
}
