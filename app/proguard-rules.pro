# R8 rules. Dependencies ship their own consumer rules for Room, Hilt, WorkManager,
# Glance, Navigation and kotlinx.serialization, so this file holds only what the
# app itself does that R8 cannot see.

# Enum constant names are written to disk and to exported files: the Room
# TypeConverters in AppDatabase.kt store Category/Frequency/GroupKind by name,
# SettingsPrefs stores ThemeMode by name, and Exporter writes the same names into
# the backup JSON. R8 renames the constants (Category.FOOD became `n` in a build
# with no rules here), which silently turns every stored "FOOD" into a valueOf
# miss, so a restored expense falls back to OTHER. The constant names are data.
-keepclassmembers enum com.splitsmart.** {
    <fields>;
}
