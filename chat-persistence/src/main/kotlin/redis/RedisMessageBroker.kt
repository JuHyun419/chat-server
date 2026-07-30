package redis

import com.chat.domain.dto.ChatMessage
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

@Service
class RedisMessageBroker(
    private val redisTemplate: RedisTemplate<String, String>,
    private val messageListenerContainer: RedisMessageListenerContainer,
    private val objectMapper: ObjectMapper
) : MessageListener {
    private val logger = LoggerFactory.getLogger(RedisMessageBroker::class.java)
    private val serverId = System.getenv("HOSTNAME") ?: "server-${System.currentTimeMillis()}"
    private val processedMessages = ConcurrentHashMap<String, Long>()
    private val subscribeRooms = ConcurrentHashMap.newKeySet<Long>()
    private var localMessageHandler: ((Long, ChatMessage) -> Unit)? = null

    fun getServerId() = serverId

    @PostConstruct
    fun initialize() {
        logger.info("Initializing RedisMessageListenerContainer")

        Thread {
            try {
                Thread.sleep(30000) // 30초
                cleanUpProcessedMessages()
            } catch (e : Exception) {
                logger.error("Error in initializing RedisMessageListenerContainer", e)
            }
        }.apply {
            isDaemon = true
            name = "redis-broker-cleanup"
            start()
        }
    }

    @PreDestroy
    fun cleanup() {
        subscribeRooms.forEach { roomId ->
            unsubscribeFromRoom(roomId)
        }
        logger.info("Removing RedisMessageListenerContainer")
    }

    fun setLocalMessageHandler(handler: (Long, ChatMessage) -> Unit) {
        this.localMessageHandler = handler
    }

    fun subscribeToRoom(roomId : Long) {
        if (subscribeRooms.add(roomId)) {
            val topic = ChannelTopic("chat.room.$roomId")
            messageListenerContainer.addMessageListener(this, topic)
            logger.info("Subscribed to $roomId")
        } else {
            logger.error("Room $roomId does not exist")
        }
    }

    fun unsubscribeFromRoom(roomId : Long) {
        if (subscribeRooms.remove(roomId)) {
            val topic = ChannelTopic("chat.room.$roomId")
            messageListenerContainer.removeMessageListener(this, topic)
            logger.info("Unsubscribed from $roomId")
        } else {
            logger.error("Room $roomId does not exist")
        }
    }

    fun broadcastToRoom(roomId : Long, message : ChatMessage, excludeSeverId: String? = null) {
        try {
            val message = DistributedMessage(
                id = "$serverId-${System.currentTimeMillis()}-${System.nanoTime()}",
                serverId = serverId,
                roomId = roomId,
                excludeSeverId = excludeSeverId,
                timestamp = LocalDateTime.now(),
                payload = message
            )

            val json = objectMapper.writeValueAsString(message)
            redisTemplate.convertAndSend("chat.room.$roomId", json)

            logger.info("Broadcast to $roomId to $json")
        } catch(e : Exception) {
            logger.error("Error broadcast to $roomId", e)
        }
    }

    override fun onMessage(message: Message, pattern: ByteArray?) {
        try {
            val json = String(message.body)
            val distributedMessage = objectMapper.readValue(json, DistributedMessage::class.java)

            if (distributedMessage.excludeSeverId == serverId) {
                logger.error("excludeSeverId to $serverId")
                return
            }

            if (processedMessages.containsKey(distributedMessage.id)) {
                logger.error("processedMessages $distributedMessage")
                return
            }

            localMessageHandler?.invoke(distributedMessage.roomId, distributedMessage.payload)

            processedMessages[distributedMessage.id] = System.currentTimeMillis()

            if (processedMessages.size > 10000) {
                val oldestEntries = processedMessages.entries.sortedBy { it.value }
                    .take(processedMessages.size - 10000)

                oldestEntries.forEach { processedMessages.remove(it.key) }
            }

            logger.info("processedMessages $distributedMessage.id")

        }  catch (e: Exception) {
            logger.error("Error in on message", e)
        }
    }

    private fun cleanUpProcessedMessages() {
        val now = System.currentTimeMillis()
        val expiredKeys = processedMessages.filter { (_, time) ->
            now - time > 60000 // 1분
        }.keys

        expiredKeys.forEach { processedMessages.remove(it) }

        if (expiredKeys.isNotEmpty()) {
            logger.info("Removed ${processedMessages.size} messages from Redis")
        }
    }

    data class DistributedMessage(
        val id : String,
        val serverId : String,
        val roomId : Long,
        val excludeSeverId : String?,
        val timestamp: LocalDateTime,
        val payload : ChatMessage
    )
}
