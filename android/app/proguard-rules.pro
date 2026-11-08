# R8 for the release build (docs/SPEC.md §37, ADR-017). Room, WorkManager, OkHttp, kotlinx-serialization and
# androidx.car.app ship their own consumer rules; the manifest keeps the activity, receivers and services.

# Readable stack traces from a release build: file names and line numbers stay, the file name attribute is generic.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Exceptions appear by class name in sync errors and logs ("Local storage failed: X"), never with content;
# without this R8 renames library exceptions (androidx.sqlite, okhttp) to a, b, c.
-keepnames class * extends java.lang.Throwable
