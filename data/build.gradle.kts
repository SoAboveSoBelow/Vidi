plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.metro)
}

android {
    namespace = "tachiyomi.data"

    sqldelight {
        databases {
            create("Database") {
                packageName.set("tachiyomi.data")
                dialect(libs.sqldelight.sqliteDialect338)
                schemaOutputDirectory.set(project.file("./src/main/sqldelight"))
                generateAsync.set(true)
                // AM (A_MIGRATION_CANNOT_BE_CHECKED_HERE) -->
                // verifyMigrations is deliberately NOT set, and this records
                // why so it is not tried again.
                //
                // With it on, SQLDelight validates each .sqm by applying the
                // migrations forward from an EMPTY schema. This project's
                // migrations start at 113 - everything before it was squashed -
                // so the first file it checks does `UPDATE episodes` against a
                // database where nothing has created episodes yet. It fails,
                // and so does every migration after it that touches a table
                // created before 113 or renamed since (animehistory,
                // animesources). Measured: it fails the build on 113, 114, 118,
                // 120, 131, 132, 133, 134, 138, 148, 171 and, as collateral,
                // on later ones whose tables never built in that synthetic
                // chain. None of those are real defects.
                //
                // It becomes usable only with a 113.db baseline schema file
                // beside the migrations, which would have to be the .sq files
                // as they stood at version 113. Those are not recoverable:
                // this repository's history is squashed to a single commit.
                //
                // What this leaves undetected is a changed .sq view or table
                // with no matching .sqm - exactly how a libraryView rewrite
                // once shipped reaching only fresh installs. The check for that
                // is to compare a real database's schema against the .sq
                // schema, which found the chain 113 -> 184 clean on a device
                // that had actually walked it: 72 of 72 objects identical.
                // <-- AM (A_MIGRATION_CANNOT_BE_CHECKED_HERE)
            }
        }
    }
}

kotlin {
    compilerOptions {
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    implementation(projects.sourceApi)
    implementation(projects.domain)
    implementation(projects.core.common)

    implementation(libs.metro.runtime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.jsonOkio)
    implementation(libs.kotlinx.serialization.protobuf)

    implementation(libs.injekt)

    implementation(libs.kotlinx.datetime)

    api(libs.bundles.sqldelight)
}
