package com.chat.application

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

@SpringBootApplication(
    scanBasePackages = [
        "com.chat.application"
//        "com.chat.domain",
//        "com.chat.persistence",
//        "com.chat.api",
//        "com.chat.websocket"
    ]
)
@EnableJpaAuditing // JPA에 대한 감사 기능 @CreatedDate
//@EnableJpaRepositories(basePackages = ["com.chat.persistence.repository"])
@EntityScan(basePackages = ["com.chat.domain.model"])
class ChatApplication

fun main(args: Array<String>) {
    runApplication<ChatApplication>(*args)
}
