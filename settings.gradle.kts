plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "chat"

include(
    "chat-application",
    "chat-domain"
)
