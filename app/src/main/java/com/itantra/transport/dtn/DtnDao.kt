package com.itantra.transport.dtn

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DtnDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: DtnMessageEntity): Long

    @Update
    suspend fun updateMessage(message: DtnMessageEntity)

    @Query("SELECT * FROM dtn_messages ORDER BY timestamp DESC LIMIT 50")
    fun getAllMessagesFlow(): Flow<List<DtnMessageEntity>>

    @Query("SELECT * FROM dtn_messages WHERE status = 'PENDING_OUTGOING' ORDER BY timestamp ASC")
    suspend fun getPendingOutgoingMessages(): List<DtnMessageEntity>

    @Query("UPDATE dtn_messages SET status = 'ACKNOWLEDGED' WHERE sequenceId = :seqId")
    suspend fun markAcknowledged(seqId: Int)

    @Query("SELECT COUNT(*) FROM dtn_messages WHERE status = 'PENDING_OUTGOING'")
    fun getPendingCountFlow(): Flow<Int>
}
