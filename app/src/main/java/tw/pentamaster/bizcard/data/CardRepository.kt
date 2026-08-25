package tw.pentamaster.bizcard.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tw.pentamaster.bizcard.util.ImageStore

class CardRepository(private val context: Context) {

    private val dao = AppDatabase.get(context).cardDao()

    fun all(): Flow<List<BusinessCard>> = dao.all()

    fun count(): Flow<Int> = dao.count()

    fun search(rawQuery: String): Flow<List<BusinessCard>> {
        val q = rawQuery.trim()
        return if (q.isEmpty()) dao.all() else dao.search(escapeLike(q))
    }

    fun byTag(tag: String): Flow<List<BusinessCard>> = dao.byTag(escapeLike(tag))

    fun allTags(): Flow<List<String>> = dao.allTagStrings().map { rows ->
        rows.flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
    }

    suspend fun byId(id: Long): BusinessCard? = dao.byId(id)

    suspend fun save(card: BusinessCard): Long {
        val stamped = card.copy(updatedAt = System.currentTimeMillis())
        return if (stamped.id == 0L) dao.insert(stamped) else {
            dao.update(stamped); stamped.id
        }
    }

    /** Deletes the row *and* the two image files, so the app doesn't leak storage. */
    suspend fun delete(card: BusinessCard) {
        dao.delete(card)
        ImageStore.delete(context, card.frontImage)
        ImageStore.delete(context, card.backImage)
    }

    suspend fun findDuplicate(card: BusinessCard): BusinessCard? =
        dao.findDuplicate(card.id, card.email.trim(), mobileDuplicateKey(card.mobile))

    companion object {
        /**
         * SQLite LIKE treats % and _ as wildcards. Without this, searching for "_"
         * returns every card and searching for "100%" returns nothing useful.
         * The backslash itself must be escaped first or it would eat the next escape.
         */
        fun escapeLike(input: String): String = input
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")


        /**
         * Taiwan mobile numbers are commonly written as either 0912... or +886 912....
         * The subscriber number (last 9 digits) is the stable duplicate key across formats.
         */
        fun mobileDuplicateKey(input: String): String {
            val digits = input.filter(Char::isDigit)
            return if (digits.length >= 9) digits.takeLast(9) else ""
        }
    }
}
