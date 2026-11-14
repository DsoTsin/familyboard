package com.familyboard.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.familyboard.protocol.*
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val paper = Color(0xFFF8F7F3)
private val green = Color(0xFF245A49)
private val red = Color(0xFFAA342C)
private val colors = lightColorScheme(primary = green, background = paper, surface = Color.White, error = red)
private const val helpMessage = "我需要帮忙，请尽快给我打电话。"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = colors) { FamilyBoardScreen() }
        }
    }
}

@Composable
private fun FamilyBoardScreen() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("familyboard", Context.MODE_PRIVATE) }
    val client = remember { BoardClient(context.applicationContext) }
    DisposableEffect(client) { onDispose { client.close() } }
    val scope = rememberCoroutineScope()
    val notice = remember { SnackbarHostState() }
    var contactName by rememberSaveable { mutableStateOf(preferences.getString("contact_name", "")!!) }
    var contactPhone by rememberSaveable { mutableStateOf(preferences.getString("contact_phone", "")!!) }
    var savedName by remember { mutableStateOf(preferences.getString("contact_name", "")!!) }
    var savedPhone by remember { mutableStateOf(preferences.getString("contact_phone", "")!!) }
    var server by rememberSaveable { mutableStateOf(preferences.getString("server", "")!!) }
    var name by rememberSaveable { mutableStateOf("") }
    var familyName by rememberSaveable { mutableStateOf("") }
    var inviteCode by rememberSaveable { mutableStateOf("") }
    var board by remember { mutableStateOf<Board?>(null) }
    var activeServer by remember { mutableStateOf<String?>(null) }
    var familyBusy by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var locationRecipient by rememberSaveable { mutableStateOf("") }
    var confirmAlert by rememberSaveable { mutableStateOf(false) }
    var invite by remember { mutableStateOf<Invite?>(null) }

    fun show(message: String) { scope.launch { notice.showSnackbar(message) } }

    fun sms(phone: String, message: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", phone, null))
                .putExtra("sms_body", message)
            context.startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("求助短信", message))
            show("无法打开短信应用，求助内容已复制。")
        }
    }

    fun locateAndText(phone: String) {
        if (locating) return
        locating = true
        scope.launch {
            try {
                val location = currentLocation(context)
                val message = if (location == null) {
                    "$helpMessage 暂时无法获取位置。"
                } else {
                    val coordinates = String.format(Locale.US, "%.5f,%.5f", location.latitude, location.longitude)
                    "$helpMessage 我的位置：https://maps.apple.com/?ll=$coordinates&q=$coordinates"
                }
                sms(phone, message)
            } finally {
                locating = false
            }
        }
    }

    val requestLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val phone = locationRecipient
        locationRecipient = ""
        if (phone.isNotEmpty()) locateAndText(phone)
    }

    fun connect(action: suspend (FamilyBoardGrpc.FamilyBoardBlockingStub) -> Unit) {
        if (familyBusy) return
        val origin = serverOrigin(server)
        if (origin == null) {
            show("请输入有效的 HTTPS 服务地址。")
            return
        }
        val endpoint = origin.toString()
        if (endpoint != activeServer) {
            board = null
            invite = null
            activeServer = endpoint
        }
        preferences.edit().putString("server", endpoint).apply()
        familyBusy = true
        scope.launch {
            try {
                action(client.connect(origin))
            } catch (error: CancellationException) {
                throw error
            } catch (error: StatusRuntimeException) {
                show(when (error.status.code) {
                    Status.Code.NOT_FOUND -> "设备尚未加入家庭，或所需的邀请或求助记录已不可用。"
                    Status.Code.INVALID_ARGUMENT -> "请检查姓名、家庭名称或邀请码的格式。"
                    Status.Code.FAILED_PRECONDITION -> "当前家庭状态不允许执行此操作。"
                    Status.Code.DEADLINE_EXCEEDED, Status.Code.UNAVAILABLE -> "暂时无法连接服务，请重试或直接用电话、短信联系。"
                    else -> "家庭服务请求失败，请重试。"
                })
            } catch (_: Exception) {
                show("家庭服务请求失败，请检查地址和设备凭据。")
            } finally {
                familyBusy = false
            }
        }
    }

    suspend fun <T> rpc(action: () -> T): T = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { action() }
    suspend fun refresh(stub: FamilyBoardGrpc.FamilyBoardBlockingStub) {
        board = rpc { stub.fresh().getBoard(GetBoardRequest.getDefaultInstance()) }
    }

    LaunchedEffect(Unit) {
        if (serverOrigin(server) != null) connect { refresh(it) }
    }

    Scaffold(containerColor = paper, snackbarHost = { SnackbarHost(notice) }) { insets ->
        Column(
            Modifier.padding(insets).fillMaxSize().verticalScroll(rememberScrollState())
                .imePadding().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("FamilyBoard", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("紧急联系家人", style = MaterialTheme.typography.bodyLarge, color = green)

            Section("紧急联系人") {
                if (savedPhone.isEmpty()) {
                    Text("保存一位家人后，就能从这里快速打电话或发短信。")
                } else {
                    Text(savedName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(savedPhone)
                }
                Button(
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", savedPhone, null)))
                        } catch (_: android.content.ActivityNotFoundException) {
                            show("此设备无法打开拨号器。")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    enabled = savedPhone.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = red)
                ) { Text("拨打电话") }
                OutlinedButton(
                    onClick = { sms(savedPhone, helpMessage) }, modifier = Modifier.fillMaxWidth(),
                    enabled = savedPhone.isNotEmpty()
                ) { Text("打开求助短信") }
                TextButton(
                    onClick = {
                        if (hasLocationPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                            locateAndText(savedPhone)
                        } else {
                            locationRecipient = savedPhone
                            requestLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }
                    }, enabled = savedPhone.isNotEmpty() && !locating
                ) { Text(if (locating) "正在获取位置…" else "附上当前位置并打开短信") }
            }

            Section("编辑首选联系人") {
                OutlinedTextField(contactName, { contactName = it }, label = { Text("姓名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(contactPhone, { contactPhone = it }, label = { Text("电话号码") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = {
                    val phone = phoneNumber(contactPhone)
                    if (phone == null || contactName.trim().isEmpty()) {
                        show("请输入姓名和有效电话号码。")
                    } else {
                        savedName = contactName.trim()
                        savedPhone = phone
                        preferences.edit().putString("contact_name", savedName).putString("contact_phone", phone).apply()
                        show("联系人已保存到这台设备。")
                    }
                }) { Text("保存联系人") }
            }

            Section("家庭组") {
                val family = board.takeIf { serverOrigin(server)?.toString() == activeServer }
                    ?.takeIf { it.hasFamily() }?.family
                OutlinedTextField(server, { server = it }, label = { Text("HTTPS 服务地址") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    enabled = !familyBusy, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { connect { refresh(it) } }, enabled = !familyBusy) { Text("连接并刷新") }

                if (family == null) {
                    OutlinedTextField(name, { name = it }, label = { Text("你的姓名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(familyName, { familyName = it }, label = { Text("新家庭名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = {
                        val request = CreateFamilyRequest.newBuilder().setDisplayName(name).setFamilyName(familyName).build()
                        connect { stub -> rpc { stub.fresh().createFamily(request) }; refresh(stub) }
                    }, enabled = !familyBusy && name.isNotBlank() && familyName.isNotBlank()) { Text("创建家庭组") }
                    OutlinedTextField(inviteCode, { inviteCode = it }, label = { Text("邀请码") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = {
                        val request = JoinFamilyRequest.newBuilder().setDisplayName(name).setInviteCode(inviteCode).build()
                        connect { stub -> rpc { stub.fresh().joinFamily(request) }; inviteCode = ""; refresh(stub) }
                    }, enabled = !familyBusy && name.isNotBlank() && inviteCode.isNotBlank()) { Text("用邀请码加入") }
                } else {
                    Text(family.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(family.membersList.joinToString("、") { it.displayName })
                    OutlinedButton(onClick = {
                        connect { stub -> invite = rpc { stub.fresh().createInvite(CreateInviteRequest.getDefaultInstance()) } }
                    }, enabled = !familyBusy) { Text("生成一次性邀请") }
                    Button(onClick = { confirmAlert = true }, enabled = !familyBusy, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = red)
                    ) { Text("向家庭组发布求助") }
                    Text("家人打开 App 并刷新后可以查看、确认求助。", style = MaterialTheme.typography.bodySmall)
                    val alerts = board?.alertsList.orEmpty()
                    if (alerts.isEmpty()) Text("暂无求助记录。")
                    alerts.forEach { alert ->
                        HorizontalDivider()
                        Text("${alert.sender.displayName} 请求帮助", fontWeight = FontWeight.SemiBold)
                        Text(DateFormat.getDateTimeInstance().format(Date(alert.createdUnixSeconds * 1000)))
                        val acknowledgements = alert.acknowledgementsList
                        Text(if (acknowledgements.isEmpty()) "等待家人确认" else
                            "已确认：${acknowledgements.joinToString("、") { it.member.displayName }}")
                        if (alert.sender.id != family.myMemberId && acknowledgements.none { it.member.id == family.myMemberId }) {
                            OutlinedButton(onClick = {
                                val request = AcknowledgeAlertRequest.newBuilder().setAlertId(alert.id).build()
                                connect { stub -> rpc { stub.fresh().acknowledgeAlert(request) }; refresh(stub) }
                            }, enabled = !familyBusy) { Text("确认收到") }
                        }
                    }
                }
                if (familyBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }

    invite?.let { current ->
        AlertDialog(
            onDismissRequest = { invite = null },
            title = { Text("邀请家人") },
            text = { Text("邀请码可使用一次，24 小时内有效。请把服务地址和邀请码发给要邀请的家人。") },
            confirmButton = {
                TextButton(onClick = {
                    val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "加入 FamilyBoard\n服务地址：$activeServer\n邀请码：${current.code}")
                    context.startActivity(Intent.createChooser(share, "分享家庭邀请"))
                    invite = null
                }) { Text("分享") }
            },
            dismissButton = { TextButton(onClick = { invite = null }) { Text("关闭") } }
        )
    }

    if (confirmAlert) {
        AlertDialog(
            onDismissRequest = { confirmAlert = false },
            title = { Text("发布求助？") },
            text = { Text("家庭成员刷新家庭组后可以看到并确认这条求助。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmAlert = false
                    val endpoint = serverOrigin(server)?.toString() ?: return@TextButton
                    val key = "pending:$endpoint"
                    val id = preferences.getString(key, null) ?: UUID.randomUUID().toString()
                    if (!preferences.edit().putString(key, id).commit()) {
                        show("无法保存求助重试状态，请直接打电话或发短信。")
                        return@TextButton
                    }
                    val request = SendAlertRequest.newBuilder().setRequestId(id).build()
                    connect { stub ->
                        rpc { stub.fresh().sendAlert(request) }
                        preferences.edit().remove(key).apply()
                        show("求助已记录，等待家人在 App 内确认。")
                        refresh(stub)
                    }
                }) { Text("发布", color = red) }
            },
            dismissButton = { TextButton(onClick = { confirmAlert = false }) { Text("取消") } }
        )
    }
}

private fun FamilyBoardGrpc.FamilyBoardBlockingStub.fresh() =
    withDeadlineAfter(7, java.util.concurrent.TimeUnit.SECONDS)

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        content()
    }
}
