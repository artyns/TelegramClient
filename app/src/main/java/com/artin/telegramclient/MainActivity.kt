package com.artin.telegramclient

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.tdlibandroid.ktx.TdClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

private val TelegramBlue = Color(0xFF229ED9)

class MainActivity : ComponentActivity() {
    private lateinit var td: TdClient
    private val scope get() = lifecycleScope

    private var auth by mutableStateOf("Starting TDLib…")
    private var authStep by mutableStateOf("loading")
    private var errorText by mutableStateOf("")
    private var chats by mutableStateOf<List<TdApi.Chat>>(emptyList())
    private var selectedChat by mutableStateOf<TdApi.Chat?>(null)
    private var messages by mutableStateOf<List<TdApi.Message>>(emptyList())
    private var phone by mutableStateOf("")
    private var code by mutableStateOf("")
    private var password by mutableStateOf("")
    private var draft by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.TELEGRAM_API_ID == 0 || BuildConfig.TELEGRAM_API_HASH == "MISSING_API_HASH") {
            authStep = "config"
            auth = "Add your Telegram API credentials"
        } else {
            startTdLib()
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFB69CFF),
                onPrimary = Color(0xFF21143D),
                secondary = Color(0xFF9B7BDB),
                background = Color(0xFF100D18),
                surface = Color(0xFF171222),
                surfaceVariant = Color(0xFF211A30),
                onBackground = Color(0xFFF2ECFF),
                onSurface = Color(0xFFF2ECFF),
                onSurfaceVariant = Color(0xFFB8ADC9)
            )) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF100D18)) {
                    when {
                        authStep == "config" -> ConfigScreen()
                        authStep == "phone" -> AuthScreen("Phone number", phone, { phone = it }, "Continue") {
                            submitPhone()
                        }
                        authStep == "code" -> AuthScreen("Login code", code, { code = it }, "Verify") {
                            submitCode()
                        }
                        authStep == "password" -> AuthScreen("Two-step verification password", password, { password = it }, "Sign in") {
                            submitPassword()
                        }
                        authStep == "ready" && selectedChat != null -> ChatScreen()
                        authStep == "ready" -> ChatsScreen()
                        else -> LoadingScreen()
                    }
                }
            }
        }
    }

    private fun startTdLib() {
        try {
            System.loadLibrary("tdjni")
            td = TdClient(
                filesDir = filesDir.resolve("tdlib").absolutePath,
                apiId = BuildConfig.TELEGRAM_API_ID,
                apiHash = BuildConfig.TELEGRAM_API_HASH,
                verbosityLevel = 1
            )
            td.init()
            scope.launch {
                td.updates.collect { update ->
                    when (update) {
                        is TdApi.UpdateAuthorizationState -> handleAuth(update.authorizationState)
                        is TdApi.UpdateNewMessage -> {
                            if (selectedChat?.id == update.message.chatId) {
                                messages = listOf(update.message) + messages
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            auth = "TDLib initialization failed: ${t.message}"
            authStep = "error"
        }
    }

    private fun handleAuth(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                auth = "Enter your phone number"
                authStep = "phone"
            }
            is TdApi.AuthorizationStateWaitCode -> {
                auth = "Enter the code sent by Telegram"
                authStep = "code"
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                auth = "Enter your two-step verification password"
                authStep = "password"
            }
            is TdApi.AuthorizationStateReady -> {
                auth = "Connected"
                authStep = "ready"
                loadChats()
            }
            is TdApi.AuthorizationStateLoggingOut -> auth = "Signing out…"
            is TdApi.AuthorizationStateClosing -> auth = "Closing…"
            is TdApi.AuthorizationStateClosed -> auth = "TDLib closed"
            else -> auth = state.javaClass.simpleName
        }
    }

    private fun submitPhone() = scope.launch {
        runCatching {
            td.send(TdApi.SetAuthenticationPhoneNumber(phone.trim(), null))
        }.onFailure { errorText = it.message ?: "Could not submit phone number" }
    }

    private fun submitCode() = scope.launch {
        runCatching {
            td.send(TdApi.CheckAuthenticationCode(code.trim()))
        }.onFailure { errorText = it.message ?: "Invalid code" }
    }

    private fun submitPassword() = scope.launch {
        runCatching {
            td.send(TdApi.CheckAuthenticationPassword(password))
        }.onFailure { errorText = it.message ?: "Invalid password" }
    }

    private fun loadChats() = scope.launch {
        runCatching {
            td.send(TdApi.LoadChats(TdApi.ChatListMain(), 100))
            val result = td.send(TdApi.GetChats(TdApi.ChatListMain(), 100))
            if (result is TdApi.Chats) {
                val loaded = mutableListOf<TdApi.Chat>()
                result.chatIds.forEach { id ->
                    val chat = td.send(TdApi.GetChat(id))
                    if (chat is TdApi.Chat) loaded += chat
                }
                chats = loaded
            }
        }.onFailure { errorText = it.message ?: "Could not load chats" }
    }

    private fun openChat(chat: TdApi.Chat) = scope.launch {
        selectedChat = chat
        messages = emptyList()
        runCatching {
            val result = td.send(TdApi.GetChatHistory(chat.id, 0, 0, 50, false))
            if (result is TdApi.Messages) messages = result.messages.reversed()
        }.onFailure { errorText = it.message ?: "Could not load messages" }
    }

    private fun sendMessage() = scope.launch {
        val chat = selectedChat ?: return@launch
        val text = draft.trim()
        if (text.isEmpty()) return@launch
        draft = ""
        runCatching {
            val formatted = TdApi.FormattedText(text, emptyArray())
            val content = TdApi.InputMessageText(formatted, null, true)
            td.send(TdApi.SendMessage(chat.id, 0, null, null, null, content))
        }.onFailure {
            errorText = it.message ?: "Message could not be sent"
            draft = text
        }
    }

    @Composable
    private fun ConfigScreen() {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Telegram Client", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("Before building, create an app at my.telegram.org and add its API credentials to local.properties.")
            Spacer(Modifier.height(12.dp))
            Text("TELEGRAM_API_ID=123456\nTELEGRAM_API_HASH=your_hash", style = MaterialTheme.typography.bodyMedium)
        }
    }

    @Composable
    private fun AuthScreen(title: String, value: String, onValue: (String) -> Unit, button: String, action: () -> Unit) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Telegram", color = Color(0xFFB69CFF), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = value, onValueChange = onValue, modifier = Modifier.fillMaxWidth(),
                singleLine = true, label = { Text(title) }, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF9B7BDB), unfocusedBorderColor = Color(0xFF3A304D), focusedTextColor = Color(0xFFF2ECFF), unfocusedTextColor = Color(0xFFF2ECFF)),
                keyboardOptions = KeyboardOptions(keyboardType = if (title == "Phone number") KeyboardType.Phone else KeyboardType.Text)
            )
            if (errorText.isNotBlank()) Text(errorText, color = Color(0xFFFF8A9A))
            Spacer(Modifier.height(16.dp))
            Button(onClick = action, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8D6AD6), contentColor = Color.White)) { Text(button) }
            Text(auth, modifier = Modifier.padding(top = 16.dp), color = Color(0xFFB8ADC9))
        }
    }

    @Composable
    private fun ChatsScreen() {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF171222)).padding(horizontal = 16.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Telegram", color = Color(0xFFEDE5FF), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            if (errorText.isNotBlank()) Text(errorText, color = Color(0xFFFF8A9A), modifier = Modifier.padding(8.dp))
            LazyColumn {
                items(chats, key = { it.id }) { chat ->
                    Column(Modifier.fillMaxWidth().clickable { openChat(chat) }.padding(horizontal = 16.dp, vertical = 13.dp)) {
                        Text(chat.title, color = Color(0xFFF2ECFF), fontWeight = FontWeight.SemiBold)
                        Text(chat.lastMessage?.let { messagePreview(it) } ?: " ", color = Color(0xFFB8ADC9), maxLines = 1)
                    }
                    HorizontalDivider(color = Color(0xFF292238), thickness = 0.6.dp)
                }
            }
        }
    }

    @Composable
    private fun ChatScreen() {
        val chat = selectedChat ?: return
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(Color(0xFF171222)).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Color.White, modifier = Modifier.clickable { selectedChat = null; loadChats() }.padding(end = 18.dp))
                Column {
                    Text(chat.title, color = Color(0xFFEDE5FF), fontWeight = FontWeight.Bold)
                    Text("Telegram chat", color = Color(0xFFB8ADC9), style = MaterialTheme.typography.labelSmall)
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(10.dp), reverseLayout = false) {
                items(messages, key = { it.id }) { message ->
                    val isOutgoing = message.isOutgoing
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start) {
                        Text(
                            messagePreview(message),
                            Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            color = if (isOutgoing) Color(0xFFD1BFFF) else Color(0xFFE9E2F5)
                        )
                    }
                }
            }
            if (errorText.isNotBlank()) Text(errorText, color = Color(0xFFFF8A9A), modifier = Modifier.padding(horizontal = 12.dp))
            Row(Modifier.fillMaxWidth().background(Color(0xFF171222)).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f), placeholder = { Text("Message", color = Color(0xFF9F94B2)) }, maxLines = 3, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF9B7BDB), unfocusedBorderColor = Color(0xFF3A304D), focusedTextColor = Color(0xFFF2ECFF), unfocusedTextColor = Color(0xFFF2ECFF))
                TextButton(onClick = { sendMessage() }) { Text("Send") }
            }
        }
    }

    @Composable
    private fun LoadingScreen() {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(auth)
            if (errorText.isNotBlank()) Text(errorText, color = Color(0xFFFF8A9A))
        }
    }

    private fun messagePreview(message: TdApi.Message): String {
        val content = message.content
        return when (content) {
            is TdApi.MessageText -> content.text.text
            is TdApi.MessagePhoto -> "Photo"
            is TdApi.MessageVideo -> "Video"
            is TdApi.MessageDocument -> content.document.fileName
            else -> content.javaClass.simpleName.removePrefix("Message")
        }
    }

    override fun onDestroy() {
        if (::td.isInitialized) runCatching { td.close() }
        super.onDestroy()
    }
}
