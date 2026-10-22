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
        'android.permission.CHANGE_WIFI_STATE', 'android.permission.NEARBY_WIFI_DEVICES',
        'android.permission.ACCESS_FINE_LOCATION', 'android.permission.ACCESS_COARSE_LOCATION'} <= perms
assert all(any(p.attrib.get(ns+'name') == name and p.attrib.get(ns+'maxSdkVersion') == '32'
               for p in manifest.findall('uses-permission'))
           for name in ('android.permission.ACCESS_FINE_LOCATION', 'android.permission.ACCESS_COARSE_LOCATION'))
assert application.attrib.get(ns + 'allowBackup') == 'false'
for attr in ('icon', 'roundIcon', 'fullBackupContent', 'dataExtractionRules'):
    assert attr in {k.removeprefix(ns) for k in application.attrib}, f'Missing {attr}'
for xml in ('backup_rules.xml', 'data_extraction_rules.xml'):
    ET.parse(root / 'app/src/main/res/xml' / xml)
ui = (root / 'app/src/main/java/com/netkit/local/ui/NetKitApp.kt').read_text()
assert 'onStartShare = onStartShare' not in ui  # no accidentally shadowed callback binding
assert 'androidx.core.content.edit' in ui
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
workflow = (root / '.github/workflows/netkit-import-build.yml').read_text()
assert './gradlew --no-daemon :app:assembleDebug' in workflow and ':app:lintDebug' in workflow
assert 'apksigner' in workflow and 'aapt' in workflow
assert (root / 'app/src/main/java/com/netkit/local/share/SharedDirectoryBrowser.kt').exists()
assert (root / 'app/src/main/java/com/netkit/local/connectivity/HotspotSettingsStore.kt').exists()
main = (root / 'app/src/main/java/com/netkit/local/app/MainActivity.kt').read_text()
assert 'RequestMultiplePermissions()' in main
assert 'ACCESS_COARSE_LOCATION' in main and 'NEARBY_WIFI_DEVICES' in main
for required in ['startShare = onStartShare', 'stopShare = onStopShare', 'onStopHotspot', '0.6 alpha']:
    assert required in ui, f'Missing {required}'
engine = (root / 'app/src/main/java/com/netkit/local/share/TransferHttpEngine.kt').read_text()
assert 'activeClients.remove(client)' in engine
assert 'x-content-sha256' in engine
assert 'UploadHashMismatchException' in engine
assert 'sha256SmallFile' in engine
script = re.search(r'<script>(.*?)</script>', engine, re.S)
assert script, 'Missing embedded browser JS'
if shutil.which('node'):
    with tempfile.TemporaryDirectory() as t:
        js = Path(t) / 'client.js'
        js.write_text(script.group(1))
        subprocess.run(['node', '--check', str(js)], check=True)
print('PASS: manifest/service wiring, client JS syntax, UI hooks and transport cleanup')
