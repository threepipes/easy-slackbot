package io.github.yusaka39.easySlackbot.slack

interface Slack {
    fun sendTo(channel: Channel, text: String)
    fun putAttachmentTo(channel: Channel, attachment: Attachment)
    fun putReactionTo(message: Message, emoticonName: String)
    fun sendDirectMessageTo(user: User, text: String)
    fun onReceiveMessage(handler: (message: Message, slack: Slack) -> Unit)
    fun onReceiveDirectMessage(handler: (message: Message, slack: Slack) -> Unit)
    fun onReceiveRepliedMessage(handler: (message: Message, slack: Slack) -> Unit)
    fun getChannelIdByName(channelName: String): String
    fun startService()
    fun stopService()
}