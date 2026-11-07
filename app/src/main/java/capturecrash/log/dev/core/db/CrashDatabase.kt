@file:JvmName("CrashDatabase")

package capturecrash.log.dev.core.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import capturecrash.log.dev.core.data.crash.Crash
import java.util.concurrent.ConcurrentHashMap

private const val DB_NAME = "crashes"
private const val DB_VERSION = 1
private const val TABLE_NAME = "Crash"

private class CrashDbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createTableIfNeeded(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        createTableIfNeeded(db)
    }

    fun createTableIfNeeded(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                _id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                time INTEGER,
                packageName TEXT,
                description TEXT,
                stackTrace TEXT
            )
            """.trimIndent()
        )
    }
}

private val instances = ConcurrentHashMap<String, CrashDbHelper>()
@Volatile
private var defaultHelper: CrashDbHelper? = null

private fun getHelper(instanceName: String = "main"): CrashDbHelper {
    return instances[instanceName]
        ?: defaultHelper
        ?: throw IllegalStateException("Database instance '$instanceName' has not been initialized.")
}

fun createDatabaseInstance(context: Context, instanceName: String?) {
    val key = instanceName ?: "main"
    val helper = CrashDbHelper(context)
    instances[key] = helper
    if (defaultHelper == null) {
        defaultHelper = helper
    }
}

fun destroyDatabaseInstance(instanceName: String?) {
    val key = instanceName ?: "main"
    instances.remove(key)?.close()
}

fun dropTable() {
    val helper = getHelper("main")
    val db = helper.writableDatabase
    db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
    helper.createTableIfNeeded(db)
}

fun deleteWhereIn(vararg selectionArgs: Any?) {
    val validArgs = selectionArgs.filterNotNull().map { it.toString() }
    if (validArgs.isEmpty()) return
    val helper = getHelper("main")
    val db = helper.writableDatabase
    helper.createTableIfNeeded(db)
    val placeholders = validArgs.joinToString(",") { "?" }
    db.delete(TABLE_NAME, "_id IN ($placeholders)", validArgs.toTypedArray())
}

fun deleteValues(values: List<Crash>) {
    if (values.isEmpty()) return
    val ids = values.map { it.id }
    deleteWhereIn(*ids.toTypedArray())
}

fun insertValues(values: List<Crash?>) {
    val helper = instances["receiver"] ?: getHelper("main")
    val db = helper.writableDatabase
    helper.createTableIfNeeded(db)
    db.beginTransaction()
    try {
        for (crash in values) {
            if (crash == null) continue
            val cv = ContentValues().apply {
                put("time", crash.time)
                put("packageName", crash.packageName)
                put("description", crash.description)
                put("stackTrace", crash.stackTrace)
            }
            val rowId = db.insert(TABLE_NAME, null, cv)
            if (rowId != -1L) {
                crash.id = rowId
            }
        }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

fun selectById(id: Long): Crash? {
    if (id <= 0L) return null
    val helper = getHelper("main")
    val db = helper.readableDatabase
    helper.createTableIfNeeded(db)
    db.query(TABLE_NAME, null, "_id = ?", arrayOf(id.toString()), null, null, null).use { cursor ->
        if (cursor.moveToFirst()) {
            val idIdx = cursor.getColumnIndex("_id")
            val timeIdx = cursor.getColumnIndex("time")
            val pkgIdx = cursor.getColumnIndex("packageName")
            val descIdx = cursor.getColumnIndex("description")
            val traceIdx = cursor.getColumnIndex("stackTrace")
            return Crash().apply {
                this.id = if (idIdx != -1) cursor.getLong(idIdx) else 0L
                this.time = if (timeIdx != -1) cursor.getLong(timeIdx) else 0L
                this.packageName = if (pkgIdx != -1) cursor.getString(pkgIdx) else ""
                this.description = if (descIdx != -1) cursor.getString(descIdx) else ""
                this.stackTrace = if (traceIdx != -1) cursor.getString(traceIdx) else ""
            }
        }
    }
    return null
}

fun selectAll(): Array<out Crash>? {
    val helper = getHelper("main")
    val db = helper.readableDatabase
    helper.createTableIfNeeded(db)
    val list = ArrayList<Crash>()
    db.query(TABLE_NAME, null, null, null, null, null, null).use { cursor ->
        val idIdx = cursor.getColumnIndex("_id")
        val timeIdx = cursor.getColumnIndex("time")
        val pkgIdx = cursor.getColumnIndex("packageName")
        val descIdx = cursor.getColumnIndex("description")
        val traceIdx = cursor.getColumnIndex("stackTrace")
        while (cursor.moveToNext()) {
            val crash = Crash().apply {
                id = if (idIdx != -1) cursor.getLong(idIdx) else 0L
                time = if (timeIdx != -1) cursor.getLong(timeIdx) else 0L
                packageName = if (pkgIdx != -1) cursor.getString(pkgIdx) else ""
                description = if (descIdx != -1) cursor.getString(descIdx) else ""
                stackTrace = if (traceIdx != -1) cursor.getString(traceIdx) else ""
            }
            list.add(crash)
        }
    }
    return if (list.isEmpty()) null else list.toTypedArray()
}
