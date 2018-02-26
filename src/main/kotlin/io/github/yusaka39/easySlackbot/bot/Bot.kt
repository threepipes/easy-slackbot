package io.github.yusaka39.easySlackbot.bot

import com.google.common.reflect.ClassPath
import io.github.yusaka39.easySlackbot.annotations.GroupParam
import io.github.yusaka39.easySlackbot.annotations.ListenTo
import io.github.yusaka39.easySlackbot.annotations.RespondTo
import org.riversun.slacklet.Slacklet
import org.riversun.slacklet.SlackletRequest
import org.riversun.slacklet.SlackletResponse
import org.riversun.slacklet.SlackletService
import org.riversun.xternal.simpleslackapi.SlackAttachment
import kotlin.reflect.KAnnotatedElement
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.valueParameters

private data class Command(val responseType: ResponseType,
                           private val regex: Regex,
                           private val kClass: KClass<*>,
                           private val kCallable: KCallable<*>) {
    fun getArgumentsFromTargetMessage(message: String): List<Any?> {
        val params = this.kCallable.valueParameters.map {
            it to (it.findAnnotation<GroupParam>()?.group ?: throw IllegalStateException(
                    "All params of methods annotated by ListenTo or RespondTo must be annotated by GroupParam."))
        }

        val groupValues = this.regex.find(message)?.groupValues ?: throw IllegalStateException("match is null")
        return params.map { (param, index) ->
            groupValues[index].convertTo(param.type)
        }
    }

    fun matches(message: String): Boolean = try {
        getArgumentsFromTargetMessage(message)
        true
    } catch (e: IllegalStateException) {
        false
    }

    fun eval(message: String): Any? {
        val parameters = this.getArgumentsFromTargetMessage(message).toTypedArray()
        val instance = this.kClass.primaryConstructor?.call() ?: throw IllegalStateException(
                "Classes contains commands must have primary constructor with no arguments"
        )
        return this.kCallable.call(instance, *parameters)
    }
}

fun String.convertTo(kType: KType): Any? {
    val isNullable = kType.isMarkedNullable

    fun Any?.validateNullability(isNullable: Boolean): Any? =
            this ?: if (isNullable) null else throw IllegalStateException()

    return when (kType.classifier) {
        Byte::class -> this.toByteOrNull().validateNullability(isNullable)
        Short::class -> this.toShortOrNull().validateNullability(isNullable)
        Int::class -> this.toIntOrNull().validateNullability(isNullable)
        Long::class -> this.toLongOrNull().validateNullability(isNullable)
        Float::class -> this.toFloatOrNull().validateNullability(isNullable)
        Double::class -> this.toDoubleOrNull().validateNullability(isNullable)
        Boolean::class -> when (this) {
            "true" -> true
            "false" -> false
            else -> null
        }
        String::class -> this
        else -> throw IllegalStateException("${kType.classifier} is not allowed as parameter")
    }
}

private enum class ResponseType {
    ListenTo, RespondTo;

    companion object {
        fun from(annotation: Annotation): ResponseType? = when (annotation) {
            is io.github.yusaka39.easySlackbot.annotations.ListenTo -> ListenTo
            is io.github.yusaka39.easySlackbot.annotations.RespondTo -> RespondTo
            else -> null
        }
    }

    fun getRegexFromAnnotation(annotation: Annotation): Regex = when (this) {
            ResponseType.ListenTo -> (annotation as? io.github.yusaka39.easySlackbot.annotations.ListenTo)?.regexp
            ResponseType.RespondTo -> (annotation as? io.github.yusaka39.easySlackbot.annotations.RespondTo)?.regexp
    }?.toRegex() ?: throw IllegalStateException("Given annotation is not a '${this.name}'")
}

private val classPath by lazy {
    ClassPath.from(ClassLoader.getSystemClassLoader())
}

private val commands: Sequence<Command> by lazy {
    classPath.allClasses.asSequence()
            .mapNotNull(ClassPath.ClassInfo::loadOrNull)
            .flatMap { kClass ->
                kClass.members.asSequence()
                        .filter {
                            it.isAnnotatedWith<ListenTo>() or it.isAnnotatedWith<RespondTo>()
                        }.map {
                            val annotation = it.annotations.first { it is ListenTo || it is RespondTo }
                            val type = ResponseType.from(annotation)!!
                            val regex = type.getRegexFromAnnotation(annotation)
                            Command(type, regex, kClass, it)
                        }
            }
}

private inline fun <reified T: Annotation> KAnnotatedElement.isAnnotatedWith() = this.annotations.any {
    it is T
}

private fun ClassPath.ClassInfo.loadOrNull(): KClass<*>? = try {
    this.load().kotlin
} catch (e: Exception) {
    null
}

class Bot(slackToken: String) {
    val slack: SlackletService by lazy {
        SlackletService(slackToken).apply {
            this.addSlacklet(object : Slacklet() {
                private fun respondTo(req: SlackletRequest, type: ResponseType) {
                    val message = req.rawPostedMessage.messageContent
                    val response = commands.firstOrNull {
                        it.responseType == type && it.matches(message)
                    }?.eval(message) ?: return
                    when (response) {
                        is String -> this@Bot.slack.sendMessageTo(req.channel, response)
                        is SlackAttachment -> this@Bot.slack.sendMessageTo(req.channel, null, response)
                        else ->
                            throw kotlin.IllegalStateException("Command methods must return string or SlackAttachment")
                    }
                }
                override fun onMentionedMessagePosted(req: SlackletRequest, resp: SlackletResponse) {
                    respondTo(req, ResponseType.RespondTo)
                }

                override fun onMessagePosted(req: SlackletRequest, resp: SlackletResponse) {
                    respondTo(req, ResponseType.ListenTo)
                }

                override fun onDirectMessagePosted(req: SlackletRequest, resp: SlackletResponse) {
                    respondTo(req, ResponseType.RespondTo)
                }
            })
        }
    }

    fun run() {
        this.slack.start()
    }

    fun kill() {
        this.slack.stop()
    }
}