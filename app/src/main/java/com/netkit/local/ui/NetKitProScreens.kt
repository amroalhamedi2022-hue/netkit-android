package com.netkit.local.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import com.netkit.local.remote.*
import com.netkit.local.transfer.TransferTelemetry
import com.netkit.local.transfer.TransferSnapshot
import com.netkit.local.transfer.TransferState
import com.netkit.local.transfer.ProgressCheckpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.provider.OpenableColumns
import com.netkit.local.app.NetKitCore

@Composable
internal fun ProDashboard(core: NetKitCore, network: String, addresses: List<String>, p: Palette,
    openShare: () -> Unit, openPeers: () -> Unit, openServers: () -> Unit, openHotspot: () -> Unit) {
    Text("مساحتك المتصلة", style=MaterialTheme.typography.titleLarge, color=p.ink)
    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxWidth().heightIn(min=206.dp).background(Brush.linearGradient(listOf(
        p.violet.copy(alpha=.39f),p.card.copy(alpha=.73f),p.aqua.copy(alpha=.16f))), RoundedCornerShape(30.dp))
        .border(1.dp,p.ink.copy(alpha=.16f),RoundedCornerShape(30.dp)).padding(24.dp)) {
        Column(Modifier.align(Alignment.CenterStart)) {
            Text("كل أجهزتك. شبكة واحدة.", fontSize=29.sp,lineHeight=36.sp,
                color=p.ink,fontWeight=FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            MutedText("نقل آمن داخل شبكتك، بدون إنترنت أو حسابات")
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                MainButton("أرسل ملفات",p,action=openShare)
                OutlinedButton(onClick=openPeers) {Text("الأجهزة القريبة")}
            }
        }
        Canvas(Modifier.size(80.dp).align(Alignment.TopEnd)) {
            drawCircle(Brush.radialGradient(listOf(p.aqua.copy(alpha=.6f),p.violet.copy(alpha=.1f),Color.Transparent)), radius=size.minDimension*.5f)
        }
    }
    Spacer(Modifier.height(15.dp))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = maxWidth > 600.dp
        if (columns) Row(horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            StatusGlass(network,addresses,p,Modifier.weight(1f))
            ActiveGlass(core,p,openShare,Modifier.weight(1f))
        } else Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
            StatusGlass(network,addresses,p)
            ActiveGlass(core,p,openShare)
        }
    }
    Spacer(Modifier.height(15.dp))
    Text("الأدوات السريعة", fontWeight=FontWeight.SemiBold,fontSize=18.sp,color=p.ink)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        ActionTile("▣", "الخادم المحلي", core.share.message, p, openServers,Modifier.weight(1f))
        ActionTile("◎", "نقطة الاتصال", core.hotspot.state.status, p, openHotspot,Modifier.weight(1f))
    }
}

@Composable private fun StatusGlass(status:String, addresses:List<String>, p:Palette, modifier:Modifier=Modifier) {
    GlassCard(modifier) {
        Text("◉  اتصالك الحالي",color=p.aqua,fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(status, color=p.ink,fontSize=17.sp)
        MutedText(addresses.joinToString("   •   ").ifBlank { "بدون عنوان IPv4 محلي" })
    }
}
@Composable private fun ActiveGlass(core:NetKitCore,p:Palette,openShare:()->Unit,modifier:Modifier=Modifier) {
    GlassCard(modifier) {
        Text("⇄  جلسة المشاركة",color=p.aqua,fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(if(core.share.active) "نشطة" else "متوقفة",color=p.ink,fontSize=17.sp)
        MutedText(core.share.message)
        if(core.share.uploadProgress.isNotEmpty()) {Spacer(Modifier.height(10.dp));LiquidProgress(core.share.uploadFraction,p)}
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick=openShare) {Text("إدارة المشاركة")}
    }
}
@Composable private fun ActionTile(symbol:String,label:String,description:String,p:Palette,action:()->Unit,modifier:Modifier) {
    GlassCard(modifier.clickable(onClick=action)) {
        Text(symbol, color=p.aqua,fontSize=25.sp)
        Spacer(Modifier.height(8.dp))
        Text(label,fontWeight=FontWeight.SemiBold,color=p.ink)
        Text(description,color=p.ink.copy(alpha=.72f),fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
    }
}

@Composable internal fun DiscoveryScreen(core:NetKitCore,p:Palette) {
    DisposableEffect(core.discovery) {
        core.discovery.start()
        onDispose { core.discovery.stop() }
    }
    Text("الأجهزة القريبة",fontWeight=FontWeight.Bold,fontSize=26.sp,color=p.ink)
    MutedText("اكتشاف الأجهزة عبر DNS-SD داخل نفس الشبكة. الظهور لا يعني الاقتران أو منح إذن نقل.")
    Spacer(Modifier.height(15.dp))
    GlassCard {
        Text("◉ البحث المحلي",color=p.aqua,fontWeight=FontWeight.Bold)
        MutedText("تُعلن الأجهزة التي تُشغّل خادم NetKit عن نفسها تلقائيًا")
        Spacer(Modifier.height(9.dp))
        if(core.discovery.found.isEmpty()) MutedText("لا توجد أجهزة مكتشفة. جرّب تشغيل المشاركة على جهاز آخر أو الاتصال بنفس Wi-Fi.")
        core.discovery.found.forEach { peer ->
            HorizontalDivider(color=p.ink.copy(alpha=.13f))
            Text(peer.name,color=p.ink,fontWeight=FontWeight.SemiBold)
            MutedText("${peer.address}:${peer.port}  •  غير مقترن")
        }
    }
    Spacer(Modifier.height(12.dp))
    GlassCard {
        Text("اتصال يدوي",fontWeight=FontWeight.Bold,color=p.ink)
        MutedText("الاكتشاف لا يلغي التحقق من هوية الجهاز. لم يتم تنفيذ الاقتران الموثّق أو النقل المباشر بين تطبيقين بعد.")
    }
}

@Composable internal fun TransferCenterScreen(core:NetKitCore,p:Palette) {
    Text("مركز النقل",fontSize=26.sp,color=p.ink,fontWeight=FontWeight.Bold)
    MutedText("السرعة والتقدم هنا يعكسان بيانات الخادم الحقيقية؛ لا توجد طوابير شكلية.")
    Spacer(Modifier.height(16.dp))
    GlassCard {
        Text("النقل الحالي",color=p.aqua,fontWeight=FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(core.share.message,color=p.ink)
        if (core.share.uploadProgress.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(core.share.uploadProgress,color=p.ink)
            LiquidProgress(core.share.uploadFraction,p)
        } else MutedText("لا توجد عملية نقل مُبلَّغ عنها الآن")
    }
    Spacer(Modifier.height(14.dp))
    GlassCard {
        Text("سجل عمليات FTP / SFTP الفعلي",color=p.ink,fontWeight=FontWeight.Bold)
        var rows by remember { mutableStateOf(core.transfers.snapshot().takeLast(30).reversed()) }
        var rates by remember { mutableStateOf<Map<String, TransferSnapshot>>(emptyMap()) }
        val estimators = remember { mutableMapOf<String, TransferTelemetry>() }
        val cleanupScope = rememberCoroutineScope()
        var confirmCleanup by remember { mutableStateOf(false) }
        var cleanupNotice by remember { mutableStateOf("") }
        LaunchedEffect(core.transfers) {
            while (true) {
                val updated = core.transfers.snapshot().takeLast(30).reversed()
                if (updated != rows) rows = updated
                // Rate derives from persisted byte changes, never synthetic animation.
                rates = updated.filter { it.state == TransferState.RUNNING }.associate { task ->
                    task.id to estimators.getOrPut(task.id) { TransferTelemetry() }
                        .sample(task.completedBytes, task.size.takeIf { it > 0 })
                }
                delay(450)
            }
        }
        val finishedCount = core.transfers.snapshot().count {
            it.state == TransferState.COMPLETED || it.state == TransferState.CANCELLED
        }
        Row(verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick={ confirmCleanup = true },enabled=finishedCount>0) {
                Text("تنظيف المكتمل والملغى ($finishedCount)")
            }
        }
        if (cleanupNotice.isNotEmpty()) MutedText(cleanupNotice)
        if (confirmCleanup) {
            AlertDialog(
                onDismissRequest={ confirmCleanup = false },
                title={ Text("تنظيف سجل النقل") },
                text={ Text("سيُحذف سجل العمليات المكتملة والملغاة فقط. لن تُحذف الملفات أو العمليات المنتظرة أو المتوقفة أو الفاشلة.") },
                confirmButton={
                    TextButton(onClick={
                        confirmCleanup = false
                        cleanupScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { core.transfers.clearFinished() }
                            }
                            cleanupNotice = result.fold(
                                onSuccess={ "تم تنظيف $it عملية من السجل دون حذف أي ملف." },
                                onFailure={ "تعذّر حفظ تنظيف السجل؛ لم تُحذف العمليات من الذاكرة." }
                            )
                        }
                    }) { Text("تأكيد التنظيف") }
                },
                dismissButton={ TextButton(onClick={ confirmCleanup = false }) { Text("إلغاء") } }
            )
        }
        if (rows.isEmpty()) MutedText("لا توجد عمليات مسجّلة بعد")
        rows.forEach { task ->
            HorizontalDivider(color=p.ink.copy(alpha=.12f))
            Text(task.destination.substringAfterLast('/'),color=p.ink,maxLines=1)
            MutedText("${task.state} · ${task.completedBytes} / ${task.size} bytes · Attempts ${task.attempts}")
            val snapshot = rates[task.id]
            if (snapshot != null && snapshot.bytesPerSecond != null && snapshot.bytesPerSecond > 0.0) {
                val mibPerSec = "%.2f".format(java.util.Locale.US, snapshot.bytesPerSecond / 1048576.0)
                val eta = snapshot.estimatedSecondsRemaining?.let { " · ETA: ${it}s" }.orEmpty()
                MutedText("مقاس فعليًا: ${mibPerSec} MiB/s${eta}")
            } else if (task.state == TransferState.RUNNING) {
                MutedText("جاري قياس السرعة...")
            }
        }
        Spacer(Modifier.height(7.dp))
        MutedText("تُحفَظ حالة النقل والبايتات المنقولة محليًا. الاستئناف الشبكي بعد الانقطاع والطابور التنفيذي الآلي لم يكتملَا بعد.")
    }
}

@Composable internal fun ServersScreen(core:NetKitCore,p:Palette,onBrowser:()->Unit) {
    Text("الخوادم والبروتوكولات",color=p.ink,fontSize=26.sp,fontWeight=FontWeight.Bold)
    MutedText("تشغيل على الشبكة المحلية، وصول مُقيّد، وإيقاف واضح لكل خدمة")
    Spacer(Modifier.height(15.dp))
    GlassCard {
        Text("🔒 HTTPS Browser Share",color=p.aqua,fontWeight=FontWeight.Bold)
        MutedText(core.share.message)
        MainButton("إعداد خادم المتصفح",p,action=onBrowser)
    }
    Spacer(Modifier.height(12.dp))
    RemoteExplorer(core,p)
    Spacer(Modifier.height(12.dp))
    GlassCard {
        Text("FTP/SFTP Server",color=p.ink,fontWeight=FontWeight.Bold)
        MutedText("الخادمان غير مفعّلين حتى تثبيت خدمة Android آمنة بصلاحيات SAF ومراجعة المصادقة؛ العميل متصل بخادم خارجي عند نجاح المصادقة؛ يلزم اختبار توافق فعلي مع خوادم متعددة.")
    }
}

@Composable private fun RemoteExplorer(core:NetKitCore,p:Palette) {
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var protocol by remember {mutableStateOf("SFTP")}
    var host by remember {mutableStateOf("")}
    var username by remember {mutableStateOf("")}
    var password by remember {mutableStateOf("")}
    var port by remember {mutableStateOf("22")}
    var fingerprint by remember {mutableStateOf("")}
    var folder by remember {mutableStateOf("/")}
    var client by remember {mutableStateOf<RemoteFileClient?>(null)}
    var entries by remember { mutableStateOf(emptyList<RemoteEntry>()) }
    var working by remember {mutableStateOf(false)}
    var result by remember {mutableStateOf("غير متصل")}
    var chosen by remember {mutableStateOf<RemoteEntry?>(null)}
    var uploadName by remember {mutableStateOf("")}
    var verifyUploadSha256 by remember {mutableStateOf(false)}
    val createDocument=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val selected=chosen
        val connected=client
        if(uri != null && selected != null && connected != null) {
            working=true
            val remotePath = folder.trimEnd('/') + "/" + selected.name
            val task = runCatching { core.transfers.add(remotePath, uri.toString(), selected.size.coerceAtLeast(0L)) }.getOrNull()
            scope.launch {
                var runningAttempt: Int? = null
                result = runCatching {
                    check(task != null) { "Transfer journal unavailable or full" }
                    val running = checkNotNull(core.transfers.begin(task.id)) { "Transfer cannot start" }
                    runningAttempt = running.attempts
                    val checkpoint = ProgressCheckpoint({ bytes -> core.transfers.progress(task.id, running.attempts, bytes) })
                    val completed = try {
                        withContext(Dispatchers.IO) {
                            connected.download(remotePath, uri, ctx) { bytes -> checkpoint.onBytes(bytes) }
                        }
                    } finally { checkpoint.flush() }
                    check(completed) { "FTP server rejected download" }
                    checkNotNull(core.transfers.finish(task.id, running.attempts)) { "Transfer length was not verified" }
                    "تم تنزيل ${selected.name}"
                }.getOrElse { failure ->
                    if(task != null) runningAttempt?.let { core.transfers.fail(task.id, it, failure.localizedMessage.orEmpty()) }
                    "فشل التنزيل: ${failure.localizedMessage}"
                }
                working=false
            }
        }
    }
    val pickUpload=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val connected=client
        if(uri != null && connected != null && uploadName.isNotBlank()) {
            working=true
            val remotePath=folder.trimEnd('/') + "/" + uploadName
            val verifyThisUpload=verifyUploadSha256
            val localSize=runCatching { ctx.contentResolver.query(uri,arrayOf(OpenableColumns.SIZE),null,null,null)
                ?.use { if(it.moveToFirst()&&!it.isNull(0)) it.getLong(0) else 0L } ?: 0L }.getOrDefault(0L)
            val task=runCatching { core.transfers.add(uri.toString(),remotePath,localSize.coerceAtLeast(0L)) }.getOrNull()
            scope.launch {
                var runningAttempt: Int? = null
                result=runCatching {
                    check(task!=null) { "Transfer journal unavailable or full" }
                    val running = checkNotNull(core.transfers.begin(task.id)) { "Transfer cannot start" }
                    runningAttempt = running.attempts
                    val checkpoint = ProgressCheckpoint({ bytes -> core.transfers.progress(task.id, running.attempts, bytes) })
                    val ok = try {
                        withContext(Dispatchers.IO) {
                            connected.upload(uri, remotePath, ctx,
                                { bytes -> checkpoint.onBytes(bytes) }, verifyThisUpload)
                        }
                    } finally { checkpoint.flush() }
                    check(ok) { "FTP server rejected upload" }
                    checkNotNull(core.transfers.finish(task.id, running.attempts)) { "Transfer length was not verified" }
                    entries=withContext(Dispatchers.IO){ connected.entries(folder) }
                    if(verifyThisUpload) "تم رفع $uploadName والتحقق من SHA-256 بقراءة الملف من الخادم"
                    else "تم رفع $uploadName (فحص الحجم فقط)"
                }.getOrElse { failure ->
                    if(task!=null) runningAttempt?.let { core.transfers.fail(task.id, it, failure.localizedMessage.orEmpty()) }
                    "فشل الرفع: ${failure.localizedMessage}"
                }
                working=false
            }
        }
    }
    DisposableEffect(client) { onDispose {runCatching {client?.close()} } }
    GlassCard {
        Text("FTP / SFTP Client",color=p.aqua,fontWeight=FontWeight.Bold)
        MutedText("الاتصال يتم مباشرة بخادم تختاره. SFTP يتطلب بصمة مفتاح SSH تم التحقق منها مسبقًا، وFTP لا يشفّر بياناته.")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("SFTP","FTP").forEach { item -> FilterChip(selected=protocol==item,onClick={
                if(client==null){protocol=item;port=if(item=="SFTP")"22" else "21"}
            },label={Text(item)}) }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(value=host,onValueChange={host=it.take(253)},label={Text("عنوان الخادم / IP")},
            singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=client==null)
        Row(horizontalArrangement=Arrangement.spacedBy(9.dp)) {
            OutlinedTextField(value=username,onValueChange={username=it},label={Text("المستخدم")},
                modifier=Modifier.weight(1f),singleLine=true,enabled=client==null)
            OutlinedTextField(value=port,onValueChange={port=it.filter(Char::isDigit).take(5)},
                label={Text("المنفذ")},modifier=Modifier.width(100.dp),singleLine=true,enabled=client==null)
        }
        OutlinedTextField(value=password,onValueChange={password=it},label={Text("كلمة المرور")},
            visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=client==null)
        if(protocol=="SFTP") {
            OutlinedTextField(value=fingerprint,onValueChange={fingerprint=it.take(100)},
                label={Text("SSH host key SHA256:... (إلزامي)")},modifier=Modifier.fillMaxWidth(),enabled=client==null)
            MutedText("تحقق من بصمة المفتاح من مالك الخادم أو إعداداته قبل الاتصال. لن نقبل مفتاحًا مجهولًا تلقائيًا.")
        }
        Spacer(Modifier.height(7.dp))
        if(client==null) MainButton(if(working)"جاري الاتصال…" else "اتصال",p,enabled=!working) {
            working=true
            scope.launch {
                val outcome=runCatching {withContext(Dispatchers.IO) {
                    val c=if(protocol=="FTP") FtpRemoteClient(host,port.toInt(),username,password,10000)
                        else SftpRemoteClient(host,port.toInt(),username,password,fingerprint,10000)
                    val contents=try {c.entries("/")} catch(error:Exception){c.close();throw error}
                    c to contents
                }}
                outcome.onSuccess { (c,files)->client=c;entries=files;folder="/";result="متصل — ${files.size} عنصر" }
                    .onFailure {result="فشل الاتصال: ${it.localizedMessage}"}
                password="";working=false
            }
        } else OutlinedButton(onClick={
            runCatching{client?.close()}; client=null;entries=emptyList();password="";result="تم قطع الاتصال"
        }) {Text("قطع الاتصال")}
        Text(result,color=p.ink.copy(alpha=.82f))
        if(client!=null) {
            OutlinedTextField(value=folder,onValueChange={folder=it},label={Text("المجلد البعيد")},modifier=Modifier.fillMaxWidth())
            OutlinedButton(enabled=!working,onClick={
                working=true
                scope.launch {result=runCatching{val listing=withContext(Dispatchers.IO){client?.entries(folder).orEmpty()};
                    entries=listing;"${listing.size} عنصر"}.getOrElse{"فشل فتح المجلد: ${it.message}"};working=false}
            }) {Text("عرض المجلد")}
            entries.forEach { entry ->
                Row(Modifier.fillMaxWidth().clickable {
                    if(entry.directory) {folder=folder.trimEnd('/')+"/"+entry.name;working=true;
                        scope.launch {result=runCatching {entries=withContext(Dispatchers.IO){client?.entries(folder).orEmpty()};"تم فتح المجلد"}.getOrElse{it.message.orEmpty()};working=false}}
                    else chosen=entry
                }.padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(if(entry.directory) "📁 " else "📄 ",color=p.ink)
                    Text(entry.name,modifier=Modifier.weight(1f),color=p.ink,maxLines=2)
                    if(!entry.directory) RadioButton(selected=chosen==entry,onClick={chosen=entry})
                }
            }
            chosen?.let {selected -> OutlinedButton(enabled=!working,onClick={createDocument.launch(selected.name)}) {
                Text("تنزيل ${selected.name}")
            }}
            OutlinedTextField(value=uploadName,onValueChange={uploadName=it.take(180)},label={Text("اسم الملف البعيد عند الرفع")},modifier=Modifier.fillMaxWidth())
            SwitchSetting(
                title="التحقق من SHA-256 بعد الرفع",
                desc="يعيد قراءة الملف كاملًا من الخادم ويقارن بصمته بالمصدر. يحتاج صلاحية قراءة على الخادم ويستهلك نقل بيانات إضافيًا؛ لا يثبت التخزين الدائم.",
                checked=verifyUploadSha256,onChange={verifyUploadSha256=it},p=p
            )
            OutlinedButton(enabled=!working && uploadName.isNotBlank(),onClick={pickUpload.launch(arrayOf("*/*"))}) {Text("رفع ملف")}
        }
    }
}

@Composable private fun SwitchSetting(title:String,desc:String,checked:Boolean,onChange:(Boolean)->Unit,p:Palette) {
    Row(Modifier.fillMaxWidth().padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {Text(title,color=p.ink,fontWeight=FontWeight.Medium);MutedTextPlaceholder(desc,p)}
        Switch(checked=checked,onCheckedChange=onChange)
    }
}
@Composable private fun MutedTextPlaceholder(text:String,p:Palette) {Text(text,color=p.ink.copy(alpha=.72f),fontSize=12.sp,lineHeight=18.sp)}

@Composable internal fun SettingsScreen(core:NetKitCore,p:Palette,onOpenShare:()->Unit,onOpenHotspot:()->Unit) {
    val cfg=core.settings
    val context= LocalContext.current
    val fullAccess=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT>=30 && Environment.isExternalStorageManager()) core.shareSharedStorage() else core.reportStoragePermissionDenied()
    }
    Text("الإعدادات",color=p.ink,fontSize=27.sp,fontWeight=FontWeight.Bold)
    MutedText("كل خيار مفعّل له أثر فعلي؛ الخيارات المقيدة بسياسات Android موضحة صراحة.")
    Spacer(Modifier.height(15.dp))
    GlassCard {
        Text("✦  المظهر والحركة",color=p.aqua,fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        listOf("Liquid Aurora — أزرق وبنفسجي", "Aqua Glass — فاتح", "Nebula Flow — وردي داكن")
            .forEachIndexed { index,label ->
                Row(Modifier.fillMaxWidth().clickable {cfg.theme(index)}.padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).background(Brush.linearGradient(listOf(paletteSamples[index].first,paletteSamples[index].second)),CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text(label,modifier=Modifier.weight(1f),color=p.ink)
                    RadioButton(selected=cfg.theme==index,onClick={cfg.theme(index)})
                }
            }
        SwitchSetting("حركة الفقاعات","تدرجات متحركة وخلفية تفاعلية",cfg.motion,{cfg.motion(it)},p)
        SwitchSetting("تقليل الحركة","يعطّل المؤثرات المستمرة لتقليل استهلاك الجهاز",cfg.reduceMotion,{cfg.reduceMotion(it)},p)
    }
    Spacer(Modifier.height(12.dp))
    GlassCard {
        Text("▣  المجلدات والمشاركة",color=p.aqua,fontWeight=FontWeight.Bold)
        SwitchSetting("القراءة فقط","عند تفعيلها يرفض HTTPS أي عملية رفع",!core.browserUploadsEnabled, {core.configureBrowserUploads(!it);cfg.readOnly(it)},p)
        Text("مدة جلسة HTTPS (بالدقائق)",color=p.ink)
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(15,30,60,120).forEach { mins ->
                FilterChip(selected=cfg.shareExpirationMinutes==mins,onClick={cfg.shareExpiration(mins)},label={Text("$mins")})
            }
        }
        MutedText("المدة تُطبّق على الجلسة الجديدة؛ أوقف الخادم وأعد تشغيله لتحديثها.")
        Spacer(Modifier.height(8.dp))
        Text("المجلد المشترك: ${core.sharedFolderStatus}",color=p.ink)
        OutlinedButton(onClick=onOpenShare) {Text("إدارة المجلدات")}
        if(Build.VERSION.SDK_INT>=30) {
            Spacer(Modifier.height(10.dp))
            Text("الذاكرة المشتركة بالكامل",color=p.ink,fontWeight=FontWeight.SemiBold)
            MutedText("إذن All files خاص، لا يمنح وصولًا لبيانات التطبيقات الخاصة. لا تفعّله إلا لو بتحتاج تصفح الذاكرة المشتركة كاملة.")
            if(core.sharedFolderStatus.contains("مساحة التخزين")) {
                Text("الوصول الكامل للشبكة المحلية مفعل عند بدء المشاركة",color=p.aqua)
                OutlinedButton(onClick={core.stopDirectoryShare()}) {Text("تعطيل مشاركة الذاكرة الكاملة")}
            } else OutlinedButton(onClick={
                if(Environment.isExternalStorageManager()) core.shareSharedStorage()
                else fullAccess.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")))
            }) { Text("طلب صلاحية أو تفعيل مشاركة الذاكرة") }
        }
    }
    Spacer(Modifier.height(12.dp))
    GlassCard {
        Text("◎  Hotspot",color=p.aqua,fontWeight=FontWeight.Bold)
        MutedText("حالة الشبكة الحقيقية، SSID وكلمة المرور وخيارات النطاق المتاحة عبر نظام جهازك فقط.")
        MutedText("التخصيص المضمون غير متاح على Android 13؛ لذلك لا نعرض إعدادات وهمية.")
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick=onOpenHotspot) {Text("فتح إعدادات Hotspot الفعلية")}
    }
    Spacer(Modifier.height(12.dp))
    GlassCard {
        Text("⛨  النقل والأمان",color=p.aqua,fontWeight=FontWeight.Bold)
        MutedText("يحفظ NetKit حالة عمليات FTP/SFTP ويستعيدها بعد إغلاق التطبيق؛ الاستكمال الفعلي والطابور متعدد العمليات يحتاجان مُنفّذ نقل ولم يُفعّلا بعد.")
        MutedText("تظهر إعدادات التوازي والإشعارات القابلة للتخصيص عندما تكون مرتبطة فعليًا بمحركات النقل.")
    }
}
private val paletteSamples=listOf(Color(0xFF53E9F9) to Color(0xFF7659EB),Color(0xFF20CBE1) to Color(0xFF95DDF1),Color(0xFFFF8A96) to Color(0xFFAB60F7))
