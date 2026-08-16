package service

import com.chat.domain.dto.ChatRoomDto
import com.chat.domain.dto.ChatRoomMemberDto
import com.chat.domain.dto.MessageDto
import com.chat.domain.dto.UserDto
import com.chat.domain.model.ChatRoom
import com.chat.domain.model.ChatRoomMember
import com.chat.domain.model.Message
import com.chat.domain.model.User
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component
import repository.ChatRoomMemberRepository
import repository.MessageRepository

@Component
class EntityDtoMapper(
    private val chatRoomMemberRepository: ChatRoomMemberRepository,
    private val messageRepository: MessageRepository,
) {
    @Cacheable(value = ["chatRooms"], key = "#chatRoom.id")
    fun chatRoomToDto(chatRoom: ChatRoom): ChatRoomDto {
        val memberCount = chatRoomMemberRepository.countActiveMembersInRoom(chatRoom.id).toInt()
        val lastMessage = messageRepository.findLatestMessage(chatRoom.id)?.let { messageToDto(it) }

        return ChatRoomDto(
            id = chatRoom.id,
            name = chatRoom.name,
            description = chatRoom.description,
            type = chatRoom.type,
            imageUrl = chatRoom.imageUrl,
            isActive = chatRoom.isActive,
            maxMembers = chatRoom.maxMembers,
            memberCount = memberCount,
            createdBy = userToDto(chatRoom.createdBy),
            createdAt = chatRoom.createdAt,
            lastMessage = lastMessage
        )
    }

    fun messageToDto(message: Message): MessageDto {
        return MessageDto(
            id = message.id,
            chatRoomId = message.chatRoom.id,
            sender = userToDto(message.sender),
            type = message.type,
            content = message.content,
            isEdited = message.isEdited,
            isDeleted = message.isDeleted,
            createdAt = message.createdAt,
            editedAt = message.editedAt,
            sequenceNumber = message.sequenceNumber
        )
    }

    fun memberToDto(member: ChatRoomMember): ChatRoomMemberDto {
        return ChatRoomMemberDto(
            id = member.id,
            user = userToDto(member.user),
            role = member.role,
            isActive = member.isActive,
            lastReadMessageId = member.lastReadMessageId,
            joinedAt = member.joinedAt,
            leftAt = member.leftAt
        )
    }

    @Cacheable(value = ["users"], key = "#user.id")
    fun userToDto(user: User): UserDto {
        return UserDto(
            id = user.id,
            username = user.username,
            displayName = user.displayName,
            profileImageUrl = user.profileImageUrl,
            status = user.status,
            isActive = user.isActive,
            lastSeenAt = user.lastSeenAt,
            createdAt = user.createdAt
        )
    }
}
