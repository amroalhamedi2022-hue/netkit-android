package com.netkit.local.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.content.Intent
import androidx.core.content.edit
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netkit.local.app.NetKitCore
import kotlinx.coroutines.delay

internal data class Palette(val aqua: Color, val violet: Color, val coral: Color, val bg: Color, val ink: Color, val card: Color)
private val palettes = listOf(
    Palette(Color(0xFF52E5FC), Color(0xFF865FF7), Color(0xFFDC6BF0), Color(0xFF080C24), Color(0xFFF2F7FF), Color(0xFF1A2147)),
    Palette(Color(0xFF0A9DCC), Color(0xFF3689EB), Color(0xFF7AC7D7), Color(0xFFF2FBFF), Color(0xFF112A45), Color(0xFFDBF1FC)),
    Palette(Color(0xFFFC8DA6), Color(0xFFB46AFA), Color(0xFFF1835E), Color(0xFF180D26), Color(0xFFFFF0F8), Color(0xFF352041))
)
private val shell = Color(0xFF080D24)
private val glass = Color.White.copy(alpha = .075f)
private val muted = Color(0xFFBAC9E0)
private val rounded = RoundedCornerShape(23.dp)
private val menu = listOf("الرئيسية", "المشاركة", "الأجهزة", "النقل", "الخوادم", "الأدوات", "Hotspot", "الإعدادات")

@Composable
fun NetKitProApp(
    core: NetKitCore,
    onRequestHotspot: () -> Unit,
    onStartShare: () -> Unit,
    onStopShare: () -> Unit,
    onStopHotspot: () -> Unit
) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val settings = core.settings
    val theme = settings.theme
    val effects = settings.motion && !settings.reduceMotion
    val palette = palettes[theme]
    var networkText by remember { mutableStateOf(core.network.summary()) }
    var ipList by remember { mutableStateOf(core.network.localAddresses()) }
    LaunchedEffect(Unit) {
        while (true) {
            networkText = core.network.summary()
            ipList = core.network.localAddresses()
            delay(3500)
        }
    }
    val filesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        if (uris.isNotEmpty()) core.addFiles(uris)
    }
    val sharedTreePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            core.shareDirectory(uri)
        }
    }
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            core.chooseFolder(uri)
        }
    }
    val scheme = if (theme == 1) lightColorScheme(
        primary = palette.aqua, secondary = palette.violet, surface = palette.card,
        background = palette.bg, onBackground = palette.ink, onSurface = palette.ink)
        else darkColorScheme(primary = palette.aqua, secondary = palette.violet,
            surface = palette.card, background = palette.bg, onBackground = palette.ink, onSurface = palette.ink)
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides palette.ink) {
            BoxWithConstraints(Modifier.fillMaxSize().background(palette.bg)) {
                val wide = maxWidth >= 740.dp
                LiquidBackdrop(palette, effects)
                Row(Modifier.fillMaxSize()) {
                    if (wide) {
                        NavigationRail(containerColor = palette.card.copy(alpha = .74f), header = {
                            Text("N", color = palette.aqua, fontSize = 30.sp, fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(vertical = 18.dp))
                        }) {
                            val symbols = listOf("⌂", "⇄", "◉", "≋", "▣", "⌁", "◎", "⚙")
                            menu.forEachIndexed { index, label ->
                                NavigationRailItem(selected = tab == index, onClick = {tab = index},
                                    icon = {Text(symbols[index], fontSize = 20.sp, color = if(tab == index) palette.aqua else palette.ink)},
                                    label = {Text(label, fontSize = 10.sp, color = palette.ink)},
                                    colors = NavigationRailItemDefaults.colors(indicatorColor = palette.violet.copy(alpha = .26f)))
                            }
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                            .padding(horizontal = if (wide) 28.dp else 18.dp, vertical = 22.dp)) {
                            BrandHeader(palette)
                            Spacer(Modifier.height(20.dp))
                            Crossfade(targetState = tab, animationSpec = tween(240), label = "netkit-screen") { activeTab ->
                                when (activeTab) {
                                0 -> ProDashboard(core, networkText, ipList, palette, { tab = 1 }, { tab = 2 }, { tab = 4 }, { tab = 6 })
                                1 -> ShareScreen(core, ipList, palette,
                                    chooseFiles = { filesPicker.launch(arrayOf("*/*")) },
                                    chooseFolder = { directoryPicker.launch(null) },
                                    chooseTree = { sharedTreePicker.launch(null) },
                                    startShare = onStartShare, stopShare = onStopShare,
                                    copy = { copy(context, it) }, shareLink = { shareLink(context, it) })
                                2 -> DiscoveryScreen(core, palette)
                                3 -> TransferCenterScreen(core, palette)
                                4 -> ServersScreen(core, palette, {tab = 1})
                                5 -> DiagnosticsScreen(core, palette)
                                6 -> HotspotScreen(core, palette, onRequestHotspot, onStopHotspot, copy = { copy(context, it) })
                                7 -> SettingsScreen(core, palette, onOpenShare = {tab=1}, onOpenHotspot = {tab=6})
                                }
                            }
                            Spacer(Modifier.height(26.dp))
                            Text("NetKit  •  Android local-first", fontSize = 11.sp,
                                color = palette.ink.copy(alpha=.6f), modifier=Modifier.align(Alignment.CenterHorizontally))
                        }
                        if (!wide) {
                            NavigationBar(containerColor = palette.card.copy(alpha = .95f), tonalElevation = 0.dp) {
                                val indices = listOf(0,1,2,4,7)
                                val symbols = listOf("⌂", "⇄", "◎", "▣", "⚙")
                                indices.forEachIndexed { idx, actual ->
                                    NavigationBarItem(selected = tab == actual, onClick={ tab = actual },
                                        icon={Text(symbols[idx], color=palette.ink, fontSize=19.sp)},
                                        label={Text(menu[actual], color=palette.ink, fontSize=10.sp)},
                                        colors=NavigationBarItemDefaults.colors(indicatorColor=palette.violet.copy(alpha=.25f)))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    val mgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    mgr.setPrimaryClip(ClipData.newPlainText("NetKit Local", text))
}

private fun shareLink(context: Context, link: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, link)
    }
    context.startActivity(Intent.createChooser(intent, "مشاركة رابط NetKit المحلي"))
}

@Composable
private fun LiquidBackdrop(p: Palette, effects: Boolean) {
    val phase = if (effects) {
        val transition = rememberInfiniteTransition(label = "liquid-aurora-motion")
        val value by transition.animateFloat(
            0f, 6.2831855f,
            infiniteRepeatable(tween(17500, easing = LinearEasing)),
            label = "slow-liquid-drift"
        )
        value
    } else 0f
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val shortest = minOf(w, h).coerceAtLeast(1f)
        val drift = kotlin.math.sin(phase.toDouble()).toFloat()
        drawRect(p.bg)
        val upperWave = Path().apply {
            moveTo(-w * .2f, h * (.07f + drift * .03f))
            cubicTo(w * .25f, h * .01f, w * .30f, h * .25f, w * .72f, h * .10f)
            cubicTo(w * 1.05f, -h * .01f, w * 1.1f, h * .40f, w * 1.3f, h * .33f)
            lineTo(w * 1.3f, -h * .2f)
            lineTo(-w * .2f, -h * .2f)
            close()
        }
        drawPath(upperWave, brush = Brush.linearGradient(
            listOf(p.violet.copy(alpha=.42f),p.aqua.copy(alpha=.16f),Color.Transparent),
            start=Offset(w*.1f,0f),end=Offset(w*.9f,h*.4f)
        ))
        val lowerWave = Path().apply {
            moveTo(-w * .1f, h * .78f)
            cubicTo(w * .15f, h * .55f, w * .58f, h * (1f + drift * .025f), w * 1.1f, h * .69f)
            lineTo(w*1.1f,h*1.2f)
            lineTo(-w*.1f,h*1.2f)
            close()
        }
        drawPath(lowerWave,brush=Brush.verticalGradient(
            listOf(p.coral.copy(alpha=.1f),p.violet.copy(alpha=.34f),p.aqua.copy(alpha=.12f)),
            startY=h*.63f,endY=h*1.1f
        ))
        val glowPools = listOf(
            Triple(Offset(w*(.20f+drift*.05f),h*.19f),p.violet,shortest*.64f),
            Triple(Offset(w*(.89f-drift*.04f),h*.39f),p.aqua,shortest*.58f),
            Triple(Offset(w*(.26f-drift*.06f),h*.86f),p.coral,shortest*.60f)
        )
        glowPools.forEach { (center,color,radius) ->
            drawCircle(brush=Brush.radialGradient(
                listOf(color.copy(alpha=.26f),color.copy(alpha=.11f),Color.Transparent),
                center=center,radius=radius
            ),radius=radius,center=center)
        }
        val bubbles = listOf(
            .07f to .12f,.89f to .16f,.63f to .28f,.13f to .44f,
            .94f to .57f,.31f to .72f,.79f to .85f,.53f to .93f
        )
        bubbles.forEachIndexed { i,pos ->
            val angle=phase+i*1.31f
            val x=(pos.first+kotlin.math.sin(angle.toDouble()).toFloat()*.013f)*w
            val y=(pos.second+kotlin.math.cos(angle.toDouble()).toFloat()*.016f)*h
            val center=Offset(x,y)
            val radius=(shortest*(.022f+(i%4)*.009f)).coerceIn(10.dp.toPx(),46.dp.toPx())
            drawCircle(brush=Brush.radialGradient(
                listOf(Color.White.copy(alpha=.32f),p.aqua.copy(alpha=.18f),
                       p.violet.copy(alpha=.14f),Color.Transparent),
                center=Offset(x-radius*.29f,y-radius*.34f),radius=radius*1.34f
            ),center=center,radius=radius)
            drawCircle(color=Color.White.copy(alpha=.30f),radius=radius,center=center,
                style=Stroke(width=1.dp.toPx()))
            drawCircle(color=Color.White.copy(alpha=.27f),radius=radius*.16f,
                center=Offset(x-radius*.43f,y-radius*.43f))
        }
    }
}

@Composable
internal fun BrandHeader(p: Palette) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(19.dp))
            .background(Brush.linearGradient(listOf(p.aqua, p.violet, p.coral))), contentAlignment = Alignment.Center) {
            Text("N", fontSize = 29.sp, fontWeight = FontWeight.Black, color = shell)
        }
        Column(Modifier.weight(1f)) {
            Text("NetKit", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = p.ink)
            Text("YOUR NETWORK, YOUR TOOLS", fontSize = 10.sp, color = p.ink.copy(alpha=.7f), letterSpacing = 1.sp)
        }
        Text("🫧", fontSize = 25.sp)
    }
}

@Composable
internal fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().clip(rounded)
        .background(Brush.linearGradient(listOf(colors.surface.copy(alpha = .78f), colors.surface.copy(alpha = .49f))))
        .border(1.dp, colors.onSurface.copy(alpha = .16f), rounded).padding(20.dp), content = content)
}

@Composable
internal fun MainButton(label: String, p: Palette, enabled: Boolean = true, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = p.aqua, contentColor = shell),
        modifier = Modifier.padding(vertical = 3.dp)) { Text(label, fontWeight = FontWeight.Bold) }
}

@Composable
internal fun MutedText(text: String) { Text(text, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.82f), lineHeight = 21.sp, fontSize = 13.sp) }

@Composable
private fun HomeScreen(core: NetKitCore, networkText: String, ips: List<String>, p: Palette,
                       onShare: () -> Unit, onHotspot: () -> Unit) {
    GlassCard(Modifier.padding(top = 7.dp)) {
        Text("● مساحة اتصالك الذكية", fontSize = 12.sp, color = p.aqua)
        Spacer(Modifier.height(15.dp))
        Text("كل أجهزتك،\nبنفس الموجة.", fontSize = 32.sp, lineHeight = 39.sp,
            fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(9.dp))
        MutedText("مشاركة ملفات محلية من هاتفك أو تابلتك مع اللابتوب، بدون خدمات سحابية.")
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
            MainButton("⇄ مشاركة الملفات", p, action = onShare)
        }
    }
    Spacer(Modifier.height(14.dp))
    GlassCard {
        Text("حالة الاتصال الفعلية", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(Modifier.height(6.dp)); MutedText(networkText)
        Spacer(Modifier.height(6.dp))
        MutedText(if (ips.isEmpty()) "لا يوجد عنوان IPv4 محلي قابل للعرض" else "العناوين المحلية: ${ips.joinToString(" • ")}")
    }
    Spacer(Modifier.height(14.dp))
    GlassCard {
        Text("✨ Smart Hotspot", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(Modifier.height(7.dp)); MutedText(core.hotspot.state.status)
        Spacer(Modifier.height(8.dp))
        MainButton(if (core.hotspot.state.active) "عرض تفاصيل الشبكة" else "إعداد شبكة محلية", p, action = onHotspot)
        MutedText("Android يحدد النطاق تلقائيًا في هذا الإصدار؛ 5GHz غير مضمون.")
    }
    Spacer(Modifier.height(14.dp))
    GlassCard {
        Text("الخادم المحلي", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(Modifier.height(8.dp)); MutedText(core.share.message)
        MainButton(if (core.share.active) "عرض رابط المشاركة" else "اختر مجلدًا أو ملفات وشاركها", p, action = onShare)
    }
}

@Composable
private fun ShareScreen(core: NetKitCore, ips: List<String>, p: Palette,
                        chooseFiles: () -> Unit, chooseFolder: () -> Unit, chooseTree: () -> Unit,
                        startShare: () -> Unit, stopShare: () -> Unit,
                        copy: (String) -> Unit, shareLink: (String) -> Unit) {
    Text("🗂️ Browser Share", fontSize = 25.sp, fontWeight = FontWeight.Bold)
    MutedText("تحميل الملفات على اللابتوب وإرسالها للتابلت عبر HTTPS المحلي.")
    Spacer(Modifier.height(15.dp))
    GlassCard {
        Text("1 • اختر ما تشاركه", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        MutedText("فقط الملفات اللي تختارها بتكون متاحة للتحميل؛ لا يمكن تصفّح بقية جهازك.")
        MainButton("＋ إضافة ملفات للتحميل", p, action = chooseFiles)
        Spacer(Modifier.height(12.dp))
        Text("مشاركة مجلد كامل بما فيه الملفات الفرعية", fontWeight = FontWeight.SemiBold)
        MutedText(core.sharedFolderStatus)
        MainButton("📁 اختيار مجلد للمشاركة", p, action = chooseTree)
        if (Build.VERSION.SDK_INT >= 30) {
            MutedText("للوصول إلى الذاكرة المشتركة كاملةً يتطلب Android إذنًا خاصًا. مجلدات Android/data الخاصة تبقى محمية.")
            MutedText("إدارة الصلاحية وإعدادات المشاركة من صفحة الإعدادات.")
        }
        core.selectedFiles.take(8).forEach { MutedText("📄 $it") }
        if (core.selectedFiles.size > 8) MutedText("+ ${core.selectedFiles.size - 8} ملفات أخرى")
        Spacer(Modifier.height(13.dp))
        Text("مجلد استقبال الملفات", fontWeight = FontWeight.Bold)
        MutedText(core.folder)
        MainButton("📁 اختيار مجلد الاستقبال", p, action = chooseFolder)
    }
    Spacer(Modifier.height(13.dp))
    GlassCard {
        Text("2 • تشغيل جلسة المشاركة", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        MutedText(core.share.message)
        if (!core.share.active) {
            MainButton(if (core.share.starting) "جاري التشغيل…" else "🔐 تشغيل HTTPS Server", p,
                enabled = !core.share.starting, action = startShare)
        } else {
            MutedText("HTTPS بشهادة محلية ذاتية التوقيع. المتصفح سيعرض تحذير شهادة عند أول اتصال؛ تحقق من البصمة أدناه.")
            ips.forEach { ip ->
                val url = "https://$ip:${core.share.port}/s/${core.share.token}/"
                Spacer(Modifier.height(9.dp))
                Text(url, color = p.aqua, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copy(url) }) { Text("نسخ الرابط") }
                    OutlinedButton(onClick = { shareLink(url) }) { Text("مشاركة الرابط") }
                }
            }
            if (ips.isEmpty()) MutedText("ما ظهر IP محلي بعد. تأكد من اتصال LAN أو Hotspot؛ ما في داعي لإعادة تشغيل الخادم.")
            Spacer(Modifier.height(12.dp))
            Text("بصمة شهادة TLS — SHA-256", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(core.share.fingerprint, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=.7f), lineHeight = 15.sp)
            Spacer(Modifier.height(10.dp))
            if (core.share.uploadProgress.isNotBlank()) {
                MutedText("استقبال: ${core.share.uploadProgress}")
                Spacer(Modifier.height(8.dp))
                LiquidProgress(core.share.uploadFraction, p)
                Spacer(Modifier.height(9.dp))
            }
            OutlinedButton(onClick = stopShare) { Text("إيقاف الخادم وإلغاء الجلسة") }
        }
        Spacer(Modifier.height(7.dp))
        MutedText("خدمة أمامية مع إشعار لإبقاء النقل أثناء إغلاق الواجهة. يعتمد التشغيل بالخلفية على نظام جهازك وإعدادات البطارية.")
    }
}

@Composable
private fun HotspotScreen(core: NetKitCore, p: Palette, start: () -> Unit, stop: () -> Unit, copy: (String) -> Unit) {
    var passwordVisible by remember { mutableStateOf(false) }
    Text("🫧 Smart Hotspot", fontSize = 25.sp, fontWeight = FontWeight.Bold)
    MutedText("أنشئ شبكة نقل محلية من التطبيق نفسه بدون مشاركة إنترنت.")
    Spacer(Modifier.height(15.dp))
    GlassCard {
        Text(core.hotspot.state.status, color = if (core.hotspot.state.active) p.aqua else Color.White, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        if (core.hotspot.state.active) {
            Text("SSID: ${core.hotspot.state.ssid}", color = Color.White)
            Text("Password: " + if (passwordVisible) core.hotspot.state.password else "••••••••", color = p.ink)
            OutlinedButton(onClick = {passwordVisible=!passwordVisible}) { Text(if(passwordVisible) "إخفاء كلمة المرور" else "إظهار كلمة المرور") }
            Spacer(Modifier.height(7.dp))
            MutedText("النطاق المحدد في إعدادات Android: ${core.hotspot.state.configuredBand}")
            MutedText("قد يختلف النطاق الفعلي الذي يستخدمه الجهاز. هذا ليس قياسًا لسرعة Wi-Fi.")
            MainButton("نسخ اسم الشبكة", p) { copy(core.hotspot.state.ssid) }
            OutlinedButton(onClick = { copy(core.hotspot.state.password) }) { Text("نسخ كلمة المرور") }
            OutlinedButton(onClick = stop) { Text("إيقاف Hotspot") }
        } else {
            MainButton("◉ تشغيل Local-only Hotspot", p,
                enabled = !core.hotspot.state.starting, action = start)
        }
    }
    Spacer(Modifier.height(13.dp))
    GlassCard {
        Text("أعلى سرعة ممكنة", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(Modifier.height(8.dp))
        MutedText("Wi-Fi 5 GHz مفضّل للنقل السريع إذا دعمه جهازا الإرسال والاستقبال وكانت الإشارة قوية.")
        MutedText("Android 13/HyperOS لا يتيح عادةً لتطبيق عادي فرض 5GHz في Local-only Hotspot. سيختار النظام النطاق، ونختبره فعليًا على Pad 5.")
        MutedText("لتحديد 5GHz يدويًا عند توفره، جرّب إعدادات نقطة الاتصال في النظام كحل بديل.")
    }
}

@Composable
internal fun DiagnosticsScreen(core: NetKitCore, p: Palette) {
    var host by remember { mutableStateOf("192.168.1.1") }
    var port by remember { mutableStateOf("443") }
    Text("⌁ أدوات الشبكات", fontSize = 25.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(14.dp))
    GlassCard {
        Text("DNS / TCP Check", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        MutedText("اختبارات حقيقية. TCP Check يقيس وقت فتح اتصال ولا يدّعي أنه ICMP Ping.")
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = host, onValueChange = { host = it },
            label = { Text("Domain أو IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
            label = { Text("TCP Port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MainButton("DNS", p, enabled = !core.busy) { core.runDns(host) }
            MainButton("TCP Check", p, enabled = !core.busy) { core.runTcp(host, port.toIntOrNull() ?: 0) }
        }
        Spacer(Modifier.height(10.dp))
        MutedText(core.result)
    }
    Spacer(Modifier.height(14.dp))
    GlassCard {
        Text("المراحل القادمة", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        MutedText("ICMP Ping الحقيقي، Traceroute، اكتشاف الأجهزة، SFTP، استكمال النقل، وقياس سرعة LAN. Wi-Fi Analyzer وRemote Control غير موجودين ضمن NetKit.")
    }
}

@Composable
private fun AppearanceScreen(theme: Int, setTheme: (Int) -> Unit, effects: Boolean,
                             setEffects: (Boolean) -> Unit, p: Palette) {
    Text("✦ مظهر NetKit", fontSize = 25.sp, fontWeight = FontWeight.Bold)
    MutedText("Liquid Gradients • Transparent Bubbles • Glass Cards")
    Spacer(Modifier.height(14.dp))
    GlassCard {
        listOf("Aurora Liquid", "Ocean Glass", "Nebula Flow").forEachIndexed { i, title ->
            Row(Modifier.fillMaxWidth().clickable { setTheme(i) }.padding(vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(39.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(palettes[i].aqua, palettes[i].violet, palettes[i].coral))))
                Spacer(Modifier.width(12.dp))
                Text(title, modifier = Modifier.weight(1f))
                RadioButton(selected = theme == i, onClick = { setTheme(i) })
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = .12f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("فقاعات ومؤثرات لطيفة", modifier = Modifier.weight(1f))
            Switch(checked = effects, onCheckedChange = setEffects)
        }
        MutedText("التدرجات ثابتة عند إطفاء المؤثرات. يتم حفظ اختياراتك على هذا الجهاز.")
    }
}


@Composable
internal fun LiquidProgress(progress: Float, p: Palette) {
    val current by animateFloatAsState(targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 210), label = "real-upload-progress")
    Box(Modifier.fillMaxWidth().height(17.dp).clip(CircleShape)
        .background(Color.White.copy(alpha = .1f))) {
        if (current > 0f) {
            Box(Modifier.fillMaxWidth(current).fillMaxHeight().clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(p.aqua, p.violet, p.coral)))) {
                Canvas(Modifier.fillMaxSize()) {
                    for (i in 0..6) {
                        val x = size.width * (i + 1f) / 8f
                        drawCircle(Color.White.copy(alpha = .28f), radius = (1.8f + i % 3) * density,
                            center = Offset(x, size.height * (if (i % 2 == 0) .30f else .72f)))
                    }
                }
            }
        }
    }
}
