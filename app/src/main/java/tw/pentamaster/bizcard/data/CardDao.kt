package tw.pentamaster.bizcard.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CardDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(card: BusinessCard): Long

    @Update
    suspend fun update(card: BusinessCard)

    @Delete
    suspend fun delete(card: BusinessCard)

    @Query("SELECT * FROM cards WHERE id = :id")
    suspend fun byId(id: Long): BusinessCard?

    @Query("SELECT * FROM cards ORDER BY updatedAt DESC")
    fun all(): Flow<List<BusinessCard>>

    @Query("SELECT * FROM cards ORDER BY updatedAt DESC")
    suspend fun allOnce(): List<BusinessCard>

    /**
     * Keyword search across every field the user might remember, including the raw OCR
     * text — so "那個穿藍色制服的公司" still turns up if any of those characters were on
     * the card, even when the parser put them in the wrong field.
     *
     * `:q` MUST be pre-escaped by [CardRepository.escapeLike] so that a user typing
     * "50%" searches for a literal percent sign instead of matching everything.
     */
    @Query(
        """
        SELECT * FROM cards WHERE
            name          LIKE '%' || :q || '%' ESCAPE '\'
         OR company       LIKE '%' || :q || '%' ESCAPE '\'
         OR title         LIKE '%' || :q || '%' ESCAPE '\'
         OR department    LIKE '%' || :q || '%' ESCAPE '\'
         OR phone         LIKE '%' || :q || '%' ESCAPE '\'
         OR mobile        LIKE '%' || :q || '%' ESCAPE '\'
         OR fax           LIKE '%' || :q || '%' ESCAPE '\'
         OR email         LIKE '%' || :q || '%' ESCAPE '\'
         OR website       LIKE '%' || :q || '%' ESCAPE '\'
         OR address       LIKE '%' || :q || '%' ESCAPE '\'
         OR tags          LIKE '%' || :q || '%' ESCAPE '\'
         OR notes         LIKE '%' || :q || '%' ESCAPE '\'
         OR rawTextFront  LIKE '%' || :q || '%' ESCAPE '\'
         OR rawTextBack   LIKE '%' || :q || '%' ESCAPE '\'
        ORDER BY updatedAt DESC
        """
    )
    fun search(q: String): Flow<List<BusinessCard>>

    /** Cards carrying an exact tag. Commas on both sides prevent "客戶" matching "潛在客戶". */
    @Query(
        "SELECT * FROM cards WHERE ',' || REPLACE(tags, ', ', ',') || ',' " +
            "LIKE '%,' || :tag || ',%' ESCAPE '\\' ORDER BY updatedAt DESC"
    )
    fun byTag(tag: String): Flow<List<BusinessCard>>

    @Query("SELECT tags FROM cards WHERE tags != ''")
    fun allTagStrings(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM cards")
    fun count(): Flow<Int>

    /**
     * Possible duplicate: same non-blank email, or same non-blank mobile.
     * Deliberately not matching on name alone — 陳志明 is not a unique key.
     */
    @Query(
        """
        SELECT * FROM cards WHERE id != :selfId AND (
            (email  != '' AND :email  != '' AND LOWER(TRIM(email)) = LOWER(TRIM(:email)))
         OR (mobile != '' AND :mobileKey != '' AND
             SUBSTR(
                 REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(mobile,'-',''),' ',''),'.',''),'(',''),')',''),'+',''),
                 -9
             ) = :mobileKey)
        ) LIMIT 1
        """
    )
    suspend fun findDuplicate(selfId: Long, email: String, mobileKey: String): BusinessCard?
}
