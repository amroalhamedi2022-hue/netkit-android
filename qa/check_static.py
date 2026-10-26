#!/usr/bin/env python3
"""Offline structural checks, NOT a replacement for Android Gradle compilation."""
from pathlib import Path
from xml.etree import ElementTree as ET
import re, shutil, subprocess, tempfile
root = Path(__file__).resolve().parents[1]
manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml').getroot()
ns = '{http://schemas.android.com/apk/res/android}'
application = manifest.find('application')
assert application.attrib[ns + 'name'] == '.app.NetKitApplication'
assert any(n.attrib.get(ns+'name') == '.app.TransferService' and
           n.attrib.get(ns+'foregroundServiceType') == 'connectedDevice'
           for n in application.findall('service'))
perms = {p.attrib[ns + 'name'] for p in manifest.findall('uses-permission')}
assert {'android.permission.FOREGROUND_SERVICE', 'android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE',
        'android.permission.CHANGE_WIFI_STATE', 'android.permission.CHANGE_WIFI_MULTICAST_STATE', 'android.permission.NEARBY_WIFI_DEVICES',
        'android.permission.ACCESS_FINE_LOCATION', 'android.permission.ACCESS_COARSE_LOCATION'} <= perms
assert all(any(p.attrib.get(ns+'name') == name and p.attrib.get(ns+'maxSdkVersion') == '32'
               for p in manifest.findall('uses-permission'))
           for name in ('android.permission.ACCESS_FINE_LOCATION', 'android.permission.ACCESS_COARSE_LOCATION'))
assert application.attrib.get(ns + 'allowBackup') == 'false'
for attr in ('icon', 'roundIcon', 'fullBackupContent', 'dataExtractionRules'):
    assert attr in {k.removeprefix(ns) for k in application.attrib}, f'Missing {attr}'
for xml in ('backup_rules.xml', 'data_extraction_rules.xml'):
    ET.parse(root / 'app/src/main/res/xml' / xml)
ui = (root / 'app/src/main/java/com/netkit/local/ui/NetKitProApp.kt').read_text()
assert 'onStartShare = onStartShare' not in ui  # no accidentally shadowed callback binding
assert 'fun NetKitProApp(' in ui and 'onStartShare' in ui
import struct
for density in ('mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi'):
    for icon_name in ('ic_launcher', 'ic_launcher_round', 'ic_launcher_foreground'):
        f = root / 'app/src/main/res' / f'mipmap-{density}' / (icon_name + '.png')
        assert f.is_file(), f'Missing icon: {f}'
        data = f.read_bytes()
        assert data[:8] == b'\x89PNG\r\n\x1a\n', f'Invalid PNG header: {f}'
        assert data[12:16] == b'IHDR' and struct.unpack('>II', data[16:24])[0] > 0
for name in ('ic_launcher', 'ic_launcher_round'):
    ET.parse(root / 'app/src/main/res/mipmap-anydpi-v26' / (name + '.xml'))
props = (root / 'gradle/wrapper/gradle-wrapper.properties').read_text()
assert 'gradle-8.13-bin.zip' in props
assert '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78' in props
workflow_path = root / '.github/workflows/netkit-import-build.yml'
if not workflow_path.exists():
    workflow_path = root / '.github/workflows/android-debug.yml'
workflow = workflow_path.read_text()
assert './gradlew --no-daemon :app:assembleDebug' in workflow and ':app:lintDebug' in workflow
main = (root / 'app/src/main/java/com/netkit/local/app/MainActivity.kt').read_text()
assert 'RequestMultiplePermissions()' in main
assert 'ACCESS_COARSE_LOCATION' in main and 'NEARBY_WIFI_DEVICES' in main
for required in ['startShare = onStartShare', 'stopShare = onStopShare', 'onStopHotspot', 'ProDashboard', 'SettingsScreen']:
    assert required in ui, f'Missing {required}'
engine = (root / 'app/src/main/java/com/netkit/local/share/TransferHttpEngine.kt').read_text()
assert 'activeClients.remove(client)' in engine
assert 'x-content-sha256' in engine
assert 'UploadHashMismatchException' in engine
assert 'sha256SmallFile' in engine
assert 'webkitdirectory' in engine and 'x-relative-path' in engine
assert 'UploadPathPolicy.folders' in engine and 'storage.uploadRelative' in engine
storage_source = (root / 'app/src/main/java/com/netkit/local/share/AndroidShareStorage.kt').read_text()
assert 'override fun uploadRelative(' in storage_source
assert 'UploadPathPolicy.checkFolders' in storage_source
assert 'uploadConsent.withCurrent(' in storage_source
assert 'uploadNames.reserve(fileName, scope)' in storage_source
assert 'downloadArchive' in engine and 'browseDirectory' in engine and 'browse-archive/' in engine
assert 'shareExpirationMinutes' in (root / 'app/src/main/java/com/netkit/local/app/NetKitCore.kt').read_text()
assert (root / 'app/src/main/java/com/netkit/local/share/DirectoryArchive.kt').is_file()
assert 'MANAGE_EXTERNAL_STORAGE' in (root / 'app/src/main/AndroidManifest.xml').read_text()
assert 'PeerDiscovery' in (root / 'app/src/main/java/com/netkit/local/app/NetKitCore.kt').read_text()
peer_discovery = (root / 'app/src/main/java/com/netkit/local/connectivity/PeerDiscovery.kt').read_text()
assert 'createMulticastLock("NetKitPeerDiscovery")' in peer_discovery
assert 'multicastLease.acquire()' in peer_discovery and 'multicastLease.release()' in peer_discovery
assert 'fun begin(' in (root / 'app/src/main/java/com/netkit/local/transfer/TransferQueue.kt').read_text()
assert 'NetKitProApp' in main
assert 'RemoteFileClient' in (root / 'app/src/main/java/com/netkit/local/ui/NetKitProScreens.kt').read_text()
script = re.search(r'<script>(.*?)</script>', engine, re.S)
assert script, 'Missing embedded browser JS'
if shutil.which('node'):
    with tempfile.TemporaryDirectory() as t:
        js = Path(t) / 'client.js'
        js.write_text(script.group(1))
        subprocess.run(['node', '--check', str(js)], check=True)
print('PASS: manifest/service wiring, client JS syntax, UI hooks and transport cleanup')
