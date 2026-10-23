#!/usr/bin/env python3
"""Audited overlay of NetKitPro features on top of current mainline.
Keeps existing SharedDirectoryBrowser and TransferHttpEngine unchanged.
"""
from pathlib import Path
import base64, hashlib, io, tarfile

root=Path(__file__).resolve().parents[1]
archive=base64.b64decode((root/'bootstrap/netkit-dev-delta-01.tar.xz.b64').read_text().strip())
assert hashlib.sha256(archive).hexdigest() == '2faa35679f7124fbb319b22e55d141e1cad0e5efd5c55633ed4f6643236e3e8d'
src={}
with tarfile.open(fileobj=io.BytesIO(archive),mode='r:xz') as t:
    for file in t.getmembers():
        if file.isfile():
            src[file.name]=t.extractfile(file).read().decode('utf-8')
base='app/src/main/java/com/netkit/local/'
def write(rel,body):
    path=root/rel
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(body,encoding='utf-8')
    print('overlay',rel)
for name in [
    base+'settings/NetKitSettings.kt',
    base+'connectivity/PeerDiscovery.kt',
    base+'remote/RemoteFileClient.kt',
]:
    write(name,src[name])
pro=src[base+'ui/NetKitApp.kt']
pro=pro.replace('fun NetKitApp(', 'fun NetKitProApp(')
pro=pro.replace('import android.content.Context','import android.content.Context\nimport android.os.Build')
pro=pro.replace('core.shareFolder(uri)','core.shareDirectory(uri)')
pro=pro.replace('core.sharedFolderLabel','core.sharedFolderStatus')
pro=pro.replace('core.storage.canRequestFullSharedStorage()','Build.VERSION.SDK_INT >= 30')
write(base+'ui/NetKitProApp.kt',pro)

screens=src[base+'ui/NetKitProScreens.kt']
screens=screens.replace('core.storage.selectedFolderLabel()', 'core.sharedFolderStatus')
screens=screens.replace('core.enableFullStorage(Build.VERSION.SDK_INT>=30 && Environment.isExternalStorageManager())',
                        'if (Build.VERSION.SDK_INT>=30 && Environment.isExternalStorageManager()) core.shareSharedStorage() else core.reportStoragePermissionDenied()')
screens=screens.replace('if(core.fullStorageEnabled)', 'if(core.sharedFolderStatus.contains("مساحة التخزين"))')
screens=screens.replace('core.enableFullStorage(false)','core.stopDirectoryShare()')
screens=screens.replace('if(Environment.isExternalStorageManager()) core.enableFullStorage(true)',
                        'if(Environment.isExternalStorageManager()) core.shareSharedStorage()')
screens=screens.replace('SwitchSetting("القراءة فقط","عند تفعيلها يرفض HTTPS أي عملية رفع",cfg.readOnly,{cfg.readOnly(it)},p)',
                        'SwitchSetting("القراءة فقط","عند تفعيلها يرفض HTTPS أي عملية رفع",!core.browserUploadsEnabled, {core.configureBrowserUploads(!it);cfg.readOnly(it)},p)')
write(base+'ui/NetKitProScreens.kt',screens)

activity=root/(base+'app/MainActivity.kt')
code=activity.read_text()
assert 'import com.netkit.local.ui.NetKitApp' in code and 'NetKitApp(core,' in code
code=code.replace('import com.netkit.local.ui.NetKitApp', 'import com.netkit.local.ui.NetKitProApp')
code=code.replace('NetKitApp(core,', 'NetKitProApp(core,')
activity.write_text(code)
print('patched MainActivity entrypoint')

core=root/(base+'app/NetKitCore.kt')
code=core.read_text()
assert 'val storage = AndroidShareStorage(context)' in code
code=code.replace('import com.netkit.local.connectivity.NetworkStatus',
    'import com.netkit.local.connectivity.NetworkStatus\nimport com.netkit.local.connectivity.PeerDiscovery\nimport com.netkit.local.settings.NetKitSettings')
code=code.replace('val storage = AndroidShareStorage(context)',
    'val storage = AndroidShareStorage(context)\n    val settings = NetKitSettings(context)\n    val discovery = PeerDiscovery(context)')
assert 'server = created\n                                Pair(created, tls.fingerprint)' in code
code=code.replace('server = created\n                                Pair(created, tls.fingerprint)',
    'server = created\n                                discovery.advertise(created.port)\n                                Pair(created, tls.fingerprint)')
assert 'share = ShareUi(message = "تم إيقاف المشاركة ومسح رمز الجلسة")' in code
code=code.replace('share = ShareUi(message = "تم إيقاف المشاركة ومسح رمز الجلسة")',
                  'discovery.stopAdvertising()\n        share = ShareUi(message = "تم إيقاف المشاركة ومسح رمز الجلسة")')
code=code.replace('hotspot.stop()\n        scope.cancel()', 'hotspot.stop()\n        discovery.close()\n        scope.cancel()')
core.write_text(code)
print('patched NetKitCore discovery/settings without replacing directory sharing')

gradle=root/'app/build.gradle.kts'
code=gradle.read_text()
assert 'implementation("androidx.compose.ui:ui")' in code
code=code.replace('    implementation("androidx.compose.ui:ui")',
'''    implementation("androidx.compose.ui:ui")
    implementation("commons-net:commons-net:3.13.0")
    implementation("com.github.mwiede:jsch:0.2.26")''')
gradle.write_text(code)
print('patched managed dependencies')
