package com.netkit.local.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.edit
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netkit.local.app.NetKitCore
import kotlinx.coroutines.delay

private data class Palette(val aqua: Color, val violet: Color, val coral: Color)
private val palettes = listOf(
    Palette(Color(0xFF49E8E8), Color(0xFF9180FF), Color(0xFFF179D2)),
    Palette(Color(0xFF3CE7E1), Color(0xFF408CFF), Color(0xFF3BE7BC)),
    Palette(Color(0xFFFFBB87), Color(0xFFB87AFF), Color(0xFFFF6AA6))
)
private val shell = Color(0xFF080D24)
private val glass = Color.White.copy(alpha = .075f)
private val muted = Color(0xFFBAC9E0)
private val rounded = RoundedCornerShape(23.dp)
private val menu = listOf("الرئيسية", "المشاركة", "الأدوات", "Hotspot", "المظهر")

@Composable
fun NetKitApp(
    core: NetKitCore,
    onRequestHotspot: () -> Unit,
    onStartShare: () -> Unit,
    onStopShare: () -> Unit,
    onStopHotspot: () -> Unit
) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val preferences = remember(context) { context.getSharedPreferences("netkit_appearance", Context.MODE_PRIVATE) }
    var theme by remember { mutableIntStateOf(preferences.getInt("theme", 0).coerceIn(0, palettes.lastIndex)) }
    var effects by remember { mutableStateOf(preferences.getBoolean("effects", true)) }
    LaunchedEffect(theme, effects) {
        preferences.edit { putInt("theme", theme); putBoolean("effects", effects) }
    }
    val palette = palettes[theme]
    val isLight = theme == 1
    val canvasBackground = if (isLight) Color(0xFFE8F8FF) else shell
    val ink = if (isLight) Color(0xFF163047) else Color.White
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
    val sharedFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                core.shareDirectory(uri)
            }
        }
    }
    val allFilesPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            runCatching { core.shareSharedStorage() }
        }
    }
    val requestSharedStorage = {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) {
                runCatching { core.shareSharedStorage() }
            } else {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"))
                allFilesPermissionLauncher.launch(intent)
            }
        }
    }
    MaterialTheme(colorScheme = if (isLight)
        lightColorScheme(primary = Color(0xFF1263A3), secondary = palette.violet,
            surface = Color(0xF4FFFFFF), background = canvasBackground, onBackground = ink,
            onSurface = ink, onSurfaceVariant = Color(0xFF456278))
        else darkColorScheme(primary = palette.aqua, secondary = palette.violet,
            surface = shell, background = canvasBackground, onBackground = ink,
            onSurface = ink, onSurfaceVariant = muted)) {
        CompositionLocalProvider(LocalContentColor provides ink) {
        Box(Modifier.fillMaxSize().background(canvasBackground)) {
            LiquidBackdrop(palette, effects)
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(
                    Modifier.weight(1f).widthIn(max = 980.dp).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp)
                ) {
                    BrandHeader(palette, onSettings = { tab = 5 })
                    Spacer(Modifier.height(16.dp))
                    Crossfade(targetState = tab, animationSpec = tween(340), label = "netkit-navigation") { activeTab ->
                    when (activeTab) {
                        0 -> HomeScreen(core, networkText, ipList, palette, onShare = { tab = 1 }, onHotspot = { tab = 3 })
                        1 -> ShareScreen(core, ipList, palette,
                            chooseFiles = { filesPicker.launch(arrayOf("*/*")) },
                            chooseFolder = { directoryPicker.launch(null) },
                            chooseSharedFolder = { sharedFolderPicker.launch(null) },
                            shareAll = requestSharedStorage,
                            stopSharedFolder = { core.stopDirectoryShare() },
                            startShare = onStartShare, stopShare = onStopShare,
                            copy = { copy(context, it) }, shareLink = { shareLink(context, it) })
                        2 -> DiagnosticsScreen(core, palette)
                        3 -> HotspotScreen(core, palette, onRequestHotspot, onStopHotspot, copy = { copy(context, it) })
                        4 -> AppearanceScreen(theme, { theme = it }, effects, { effects = it }, palette)
                        else -> SettingsScreen(core, palette,
                            selectFolder = { sharedFolderPicker.launch(null) },
                            allStorage = requestSharedStorage,
                            stopFolder = { core.stopDirectoryShare() },
                            chooseReceive = { directoryPicker.launch(null) },
                            changeTheme = { tab = 4 },
                            hotspotSettings = { tab = 3 })
                    }
                    }
                    Spacer(Modifier.height(30.dp))
                    Text("NetKit • v0.6 alpha • Local-first", style = MaterialTheme.typography.labelSmall,
                        color = muted.copy(alpha = .7f), modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                NavigationBar(containerColor = if (isLight) Color(0xF0E6F5FF) else Color(0xE20C1430), tonalElevation = 0.dp) {
                    val emoji = listOf("⌂", "⇄", "⌁", "◉", "✦")
                    val navMuted = if (isLight) Color(0xFF38546F) else muted
                    val navAccent = if (isLight) Color(0xFF205DAD) else palette.aqua
                    menu.forEachIndexed { idx, title ->
                        NavigationBarItem(selected = tab == idx, onClick = { tab = idx },
                            icon = { Text(emoji[idx], fontSize = 20.sp, color = if (tab == idx) navAccent else navMuted) },
                            label = { Text(title, fontSize = 10.sp, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = navAccent, selectedTextColor = ink,
                                unselectedIconColor = navMuted, unselectedTextColor = navMuted,
                                indicatorColor = palette.violet.copy(alpha = .20f)))
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
    val infinite = rememberInfiniteTransition(label = "liquid-bubbles")
    val float by infinite.animateFloat(0f, 1f,
        infiniteRepeatable(tween(12500), RepeatMode.Reverse), label = "bubble-offset")
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        // Layered translucent liquid ribbons: gently animated, behind all interactive content.
        val shift = if (effects) (float - .5f) * 0.08f * h else 0f
        val ribbon = Path().apply {
            moveTo(-.12f * w, .19f * h + shift)
            cubicTo(.15f * w, .02f * h + shift, .60f * w, .35f * h, 1.12f * w, .12f * h)
            lineTo(1.12f * w, .30f * h)
            cubicTo(.62f * w, .52f * h, .28f * w, .22f * h + shift, -.12f * w, .45f * h)
            close()
        }
        drawPath(ribbon, Brush.linearGradient(
            listOf(p.aqua.copy(alpha = .12f), p.violet.copy(alpha = .16f), p.coral.copy(alpha = .09f)),
            start = Offset(0f, 0f), end = Offset(w, h * .58f)))
        val lowerRibbon = Path().apply {
            moveTo(-.1f * w, .75f * h)
            cubicTo(.24f * w, .59f * h + shift, .63f * w, .97f * h, 1.1f * w, .69f * h)
            lineTo(1.1f * w, 1.12f * h)
            lineTo(-.1f * w, 1.12f * h)
            close()
        }
        drawPath(lowerRibbon, Brush.linearGradient(
            listOf(p.coral.copy(alpha = .10f), p.violet.copy(alpha = .19f), p.aqua.copy(alpha = .12f)),
            start = Offset(0f, h * .6f), end = Offset(w, h)))
        drawRect(brush = Brush.radialGradient(
            listOf(p.violet.copy(alpha = .28f), Color.Transparent),
            center = Offset(size.width * .05f, size.height * .05f), radius = size.width * .9f))
        drawRect(brush = Brush.radialGradient(
            listOf(p.aqua.copy(alpha = .16f), Color.Transparent),
            center = Offset(size.width, size.height * .45f), radius = size.width * .92f))
        drawRect(brush = Brush.radialGradient(
            listOf(p.coral.copy(alpha = .14f), Color.Transparent),
            center = Offset(size.width * .38f, size.height), radius = size.width * .86f))
        if (effects) {
            listOf(Offset(.12f, .22f), Offset(.86f, .17f), Offset(.78f, .76f), Offset(.25f, .86f))
                .forEachIndexed { i, pos ->
                    val r = 15.dp.toPx() + i * 7.dp.toPx()
                    val c = Offset(pos.x * size.width, pos.y * size.height + (float - .5f) * (i + 1) * 18.dp.toPx())
                    drawCircle(brush = Brush.radialGradient(listOf(Color.White.copy(alpha = .21f),
                        p.violet.copy(alpha = .16f), Color.Transparent), c, r), radius = r, center = c)
                    drawCircle(Color.White.copy(alpha = .19f), radius = r, center = c, style = Stroke(1.2.dp.toPx()))
                }
        }
    }
}

@Composable
private fun BrandHeader(p: Palette, onSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(19.dp))
            .background(Brush.linearGradient(listOf(p.aqua, p.violet, p.coral))), contentAlignment = Alignment.Center) {
            Text("N", fontSize = 29.sp, fontWeight = FontWeight.Black, color = shell)
        }
        Column(Modifier.weight(1f)) {
            Text("NetKit", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text("YOUR NETWORK, YOUR TOOLS", fontSize = 10.sp, color = muted, letterSpacing = 1.sp)
        }
        IconButton(onClick = onSettings) { Text("⚙", fontSize = 25.sp, color = MaterialTheme.colorScheme.onSurface) }
    }
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val light = MaterialTheme.colorScheme.background.red > .75f
    val fill = if (light)
        listOf(Color.White.copy(alpha = .88f), Color(0xFFCBEDF8).copy(alpha = .66f), Color.White.copy(alpha = .7f))
    else listOf(Color.White.copy(alpha = .155f), MaterialTheme.colorScheme.secondary.copy(alpha = .13f),
        Color.White.copy(alpha = .075f))
    val rim = if (light) listOf(Color.White, Color(0xFF9ED8F2).copy(alpha = .65f))
        else listOf(Color.White.copy(alpha = .31f), MaterialTheme.colorScheme.primary.copy(alpha = .34f))
    Column(modifier.fillMaxWidth()
        .shadow(if (light) 8.dp else 12.dp, rounded)
        .clip(rounded)
        .background(Brush.linearGradient(fill))
        .border(1.dp, Brush.linearGradient(rim), rounded)
        .padding(20.dp), content = content)
}

@Composable
private fun MainButton(label: String, p: Palette, enabled: Boolean = true, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = p.aqua, contentColor = shell),
        modifier = Modifier.padding(vertical = 3.dp)) { Text(label, fontWeight = FontWeight.Bold) }
}

@Composable
private fun MutedText(text: String) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 21.sp, fontSize = 13.sp) }

@Composable
private fun HomeScreen(core: NetKitCore, networkText: String, ips: List<String>, p: Palette,
                       onShare: () -> Unit, onHotspot: () -> Unit) {
    Spacer(Modifier.height(5.dp))
    GlassCard {
        Text("● مساحة اتصالك الذكية", fontSize = 12.sp, color = p.aqua)
        Spacer(Modifier.height(11.dp))
        Text("كل أجهزتك، بنفس الموجة.", fontSize = 29.sp, lineHeight = 37.sp,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(7.dp))
        MutedText("شارك مجلداتك أو ذاكرة الجهاز المشتركة عبر HTTPS محلي، وانقل الملفات بدون سحابة.")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MainButton("⇄ مركز نقل الملفات", p, action = onShare)
        }
    }
    Spacer(Modifier.height(12.dp))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 650.dp
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    HomeConnectionCard(networkText, ips, p)
                    Spacer(Modifier.height(12.dp))
                    HomeHotspotCard(core, p, onHotspot)
                }
                Column(Modifier.weight(1f)) {
                    HomeShareCard(core, p, onShare)
                    Spacer(Modifier.height(12.dp))
                    GlassCard {
                        Text("📂 نطاق المشاركة", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        MutedText(core.sharedFolderStatus)
                        MutedText("المشاركة تتوقف فور إغلاق الجلسة، والملفات الخاصة للتطبيقات محمية من النظام.")
                    }
                }
            }
        } else {
            HomeConnectionCard(networkText, ips, p)
            Spacer(Modifier.height(12.dp))
            HomeShareCard(core, p, onShare)
            Spacer(Modifier.height(12.dp))
            HomeHotspotCard(core, p, onHotspot)
            Spacer(Modifier.height(12.dp))
            GlassCard {
                Text("📂 نطاق المشاركة", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                MutedText(core.sharedFolderStatus)
            }
        }
    }
}

@Composable
private fun HomeConnectionCard(networkText: String, ips: List<String>, p: Palette) {
    GlassCard {
        Text("◉ حالة الشبكة المحلية", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(Modifier.height(8.dp))
        Text(networkText, color = p.aqua, fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        MutedText(if (ips.isEmpty()) "لا توجد عناوين IPv4 متاحة"
            else "العناوين: ${ips.joinToString(" • ")}")
    }
}

@Composable
private fun HomeShareCard(core: NetKitCore, p: Palette, onShare: () -> Unit) {
    GlassCard {
        Text("⇄ Browser File Transfer", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(Modifier.height(6.dp))
        MutedText(core.share.message)
        Spacer(Modifier.height(6.dp))
        MainButton(if (core.share.active) "إدارة جلسة المشاركة" else "إعداد مشاركة الملفات", p, action = onShare)
    }
}

@Composable
private fun HomeHotspotCard(core: NetKitCore, p: Palette, onHotspot: () -> Unit) {
    GlassCard {
        Text("◉ Local-only Hotspot", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(Modifier.height(6.dp))
        MutedText(core.hotspot.state.status)
        Spacer(Modifier.height(6.dp))
        MainButton(if (core.hotspot.state.active) "عرض الشبكة الفعلية" else "إعداد شبكة محلية", p, action = onHotspot)
    }
}

@Composable
private fun ShareScreen(core: NetKitCore, ips: List<String>, p: Palette,
                        chooseFiles: () -> Unit, chooseFolder: () -> Unit,
                        chooseSharedFolder: () -> Unit, shareAll: () -> Unit, stopSharedFolder: () -> Unit,
                        startShare: () -> Unit, stopShare: () -> Unit,
                        copy: (String) -> Unit, shareLink: (String) -> Unit) {
    Text("🗂️ Browser Share", fontSize = 25.sp, fontWeight = FontWeight.Bold)
    MutedText("تحميل الملفات على اللابتوب وإرسالها للتابلت عبر HTTPS المحلي.")
    Spacer(Modifier.height(15.dp))
    GlassCard {
        Text("1 • اختر ما تشاركه", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        MutedText("فقط الملفات اللي تختارها بتكون متاحة للتحميل؛ لا يمكن تصفّح بقية جهازك.")
        MainButton("＋ إضافة ملفات للتحميل", p, action = chooseFiles)
        core.selectedFiles.take(8).forEach { MutedText("📄 $it") }
        if (core.selectedFiles.size > 8) MutedText("+ ${core.selectedFiles.size - 8} ملفات أخرى")
        Spacer(Modifier.height(13.dp))
        Text("مجلد استقبال الملفات", fontWeight = FontWeight.Bold)
        MutedText(core.folder)
        MainButton("📁 اختيار مجلد الاستقبال", p, action = chooseFolder)
    }
    Spacer(Modifier.height(13.dp))
    GlassCard {
        Text("📁 مشاركة مجلد أو التخزين المشترك", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        MutedText("مش لازم تختار كل ملف لحاله. شارك مجلد كامل مع مجلداته الفرعية، أو مساحة التخزين المشتركة بعد منح صلاحية خاصة.")
        Spacer(Modifier.height(8.dp))
        MutedText(core.sharedFolderStatus)
        MainButton("📂 اختيار مجلد كامل للمشاركة", p, action = chooseSharedFolder)
        Spacer(Modifier.height(8.dp))
        MainButton("🗄️ مشاركة التخزين الداخلي المشترك", p,
            enabled = Build.VERSION.SDK_INT >= 30, action = shareAll)
        MutedText("هذا الخيار يطلب صلاحية All files access من Android؛ لا يتيح ملفات التطبيقات الخاصة أو المجلدات المحمية.")
        OutlinedButton(onClick = stopSharedFolder) { Text("إيقاف مشاركة المجلدات") }
        MutedText("تغيير نطاق المشاركة يوقف جلسة HTTPS الحالية؛ شغّل الجلسة مجددًا لتوليد رابط جديد.")
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
            Text(core.share.fingerprint, fontSize = 10.sp, color = muted, lineHeight = 15.sp)
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
    val context = LocalContext.current
    var name by remember { mutableStateOf(core.hotspot.preferredName) }
    var password by remember { mutableStateOf(core.hotspot.preferredPassword) }
    var band by remember { mutableStateOf(core.hotspot.preferredBand) }
    var feedback by remember { mutableStateOf("") }
    var revealPassword by remember { mutableStateOf(false) }
    Text("🫧 Smart Hotspot", fontSize = 25.sp, fontWeight = FontWeight.Bold)
    MutedText("أنشئ شبكة نقل محلية من التطبيق نفسه بدون مشاركة إنترنت.")
    Spacer(Modifier.height(15.dp))
    GlassCard {
        Text(core.hotspot.state.status, color = if (core.hotspot.state.active) p.aqua else Color.White, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        if (core.hotspot.state.active) {
            Text("SSID: ${core.hotspot.state.ssid}", color = MaterialTheme.colorScheme.onSurface)
            Text("Password: " + if (revealPassword) core.hotspot.state.password else "••••••••", color = muted)
            OutlinedButton(onClick = { revealPassword = !revealPassword }) {
                Text(if (revealPassword) "إخفاء كلمة المرور" else "إظهار كلمة المرور")
            }
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
        Text("إعدادات نقطة الاتصال", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
        if (core.hotspot.supportsCustomSettings) {
            MutedText("هذه الإعدادات تُحفظ وتُطبّق عند بدء Local-only Hotspot على Android API 36 أو أحدث.")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(name, { name = it.take(32) }, label = { Text("SSID • اسم الشبكة") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                enabled = !core.hotspot.state.active && !core.hotspot.state.starting)
            OutlinedTextField(password, { password = it.take(63) }, label = { Text("WPA2 • كلمة المرور (8–63)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = if (revealPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { revealPassword = !revealPassword }) {
                        Text(if (revealPassword) "إخفاء" else "إظهار")
                    }
                },
                enabled = !core.hotspot.state.active && !core.hotspot.state.starting)
            Text("النطاق المفضّل", color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("auto" to "تلقائي", "2.4" to "2.4 GHz", "5" to "5 GHz").forEach { (code, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(enabled = !core.hotspot.state.active) { band = code }) {
                        RadioButton(selected = band == code, onClick = { band = code },
                            enabled = !core.hotspot.state.active)
                        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                    }
                }
            }
            MainButton("حفظ إعدادات Hotspot", p,
                enabled = !core.hotspot.state.active && !core.hotspot.state.starting) {
                feedback = runCatching {
                    core.hotspot.updateConfiguration(name, password, band)
                    "تم حفظ الإعدادات؛ سيتم تطبيقها عند التشغيل المقبل."
                }.getOrElse { it.localizedMessage ?: "الإعدادات غير صالحة" }
            }
            if (feedback.isNotBlank()) MutedText(feedback)
        } else {
            MutedText("على Android ${Build.VERSION.SDK_INT} لا يسمح LocalOnlyHotspot للتطبيق بتثبيت SSID أو كلمة المرور أو اختيار 2.4/5GHz. القيم المعروضة أعلاه مولّدة من النظام وقد تتغير عند التشغيل.")
            MutedText("خيار تعديل اسم/كلمة سر نقطة اتصال الإنترنت العادية موجود في إعدادات Android إن دعمته الشركة المصنّعة؛ هذه خدمة مختلفة وقد تشارك الإنترنت.")
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            }) { Text("فتح إعدادات الاتصال بالنظام") }
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
private fun DiagnosticsScreen(core: NetKitCore, p: Palette) {
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
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("فقاعات ومؤثرات لطيفة", modifier = Modifier.weight(1f))
            Switch(checked = effects, onCheckedChange = setEffects)
        }
        MutedText("التدرجات ثابتة عند إطفاء المؤثرات. يتم حفظ اختياراتك على هذا الجهاز.")
    }
}


@Composable
private fun SettingsScreen(core: NetKitCore, p: Palette, selectFolder: () -> Unit,
    allStorage: () -> Unit, stopFolder: () -> Unit, chooseReceive: () -> Unit,
    changeTheme: () -> Unit, hotspotSettings: () -> Unit) {
    Text("⚙ إعدادات NetKit", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 25.sp)
    MutedText("إعدادات حقيقية للميزات المتوفرة حاليًا. الإعدادات المتقدمة للنقل و FTP/SFTP ستظهر عند تنفيذ محركاتها.")
    Spacer(Modifier.height(14.dp))
    GlassCard {
        Text("التخزين والمشاركة", color = MaterialTheme.colorScheme.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        MutedText(core.sharedFolderStatus)
        MainButton("اختيار مجلد مشاركة كامل", p, action = selectFolder)
        MainButton("تفعيل مشاركة التخزين المشترك", p,
            enabled = Build.VERSION.SDK_INT >= 30, action = allStorage)
        OutlinedButton(onClick = stopFolder) { Text("تعطيل مشاركة المجلدات") }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .15f))
        MutedText("مجلد استقبال الملفات: ${core.folder}")
        OutlinedButton(onClick = chooseReceive) { Text("تغيير مجلد الاستقبال") }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .15f))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("السماح باستقبال الملفات عبر المتصفح", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Switch(checked = core.browserUploadsEnabled,
                onCheckedChange = { core.configureBrowserUploads(it) })
        }
        MutedText("عند تعطيله تبقى المشاركة للقراءة والتنزيل فقط، حتى لو تم اختيار مجلد استقبال.")
        Spacer(Modifier.height(8.dp))
        Text("حدّ حجم الملف المرسل", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
        listOf(512 to "512 MB", 2048 to "2 GB", 16384 to "16 GB").forEach { (size, label) ->
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { core.configureUploadLimit(size) }) {
                RadioButton(selected = core.maxBrowserUploadMiB == size,
                    onClick = { core.configureUploadLimit(size) })
                Text(label, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    GlassCard {
        Text("الشبكة والشكل", color = MaterialTheme.colorScheme.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = hotspotSettings) { Text("إعدادات Local Hotspot") }
        OutlinedButton(onClick = changeTheme) { Text("الثيمات والحركة") }
    }
}

@Composable
private fun LiquidProgress(progress: Float, p: Palette) {
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
