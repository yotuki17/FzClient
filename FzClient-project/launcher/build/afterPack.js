// Puts the FzClient icon and version info into the .exe (pure JS, no Wine needed).
const fs = require('fs');
const path = require('path');
const ResEdit = require('resedit');

exports.default = async function (context) {
  if (context.electronPlatformName === 'darwin') return macAdHoc(context);
  if (context.electronPlatformName !== 'win32') return;
  const exe = path.join(context.appOutDir, `${context.packager.appInfo.productFilename}.exe`);
  const data = fs.readFileSync(exe);
  const pe = ResEdit.NtExecutable.from(data, { ignoreCert: true });
  const res = ResEdit.NtExecutableResource.from(pe);
  const ico = ResEdit.Data.IconFile.from(fs.readFileSync(path.join(__dirname, 'icon.ico')));
  const groups = ResEdit.Resource.IconGroupEntry.fromEntries(res.entries);
  const groupId = groups.length ? groups[0].id : 1;
  const lang = groups.length ? groups[0].lang : 1033;
  ResEdit.Resource.IconGroupEntry.replaceIconsForResource(res.entries, groupId, lang, ico.icons.map(i => i.data));
  const vi = ResEdit.Resource.VersionInfo.fromEntries(res.entries)[0];
  if (vi) {
    const v = context.packager.appInfo.version.split('.').map(Number);
    vi.setFileVersion(v[0], v[1], v[2], 0, 1033);
    vi.setProductVersion(v[0], v[1], v[2], 0, 1033);
    vi.setStringValues({ lang: 1033, codepage: 1200 }, {
      FileDescription: 'FzClient Launcher', ProductName: 'FzClient Launcher', CompanyName: 'FzClient',
      OriginalFilename: 'FzClient Launcher.exe', InternalName: 'FzClient Launcher', LegalCopyright: 'FzClient',
    });
    vi.outputToResourceEntries(res.entries);
  }
  res.outputResource(pe);
  fs.writeFileSync(exe, Buffer.from(pe.generate()));
};

/**
 * A Mac on Apple Silicon only runs a program with a valid signature. The app is not signed by Apple, so it gets an
 * "ad-hoc" one (no certificate). electron-builder signs it this way too (mac.identity "-"); doing it here as well
 * makes sure the dmg / zip never carry a broken signature even if that step is skipped.
 */
function macAdHoc(context) {
  const { spawnSync } = require('child_process');
  const app = path.join(context.appOutDir, context.packager.appInfo.productFilename + '.app');
  const r = spawnSync('codesign', ['--force', '--deep', '--sign', '-', app], { encoding: 'utf8' });
  if (r.status !== 0) throw new Error('codesign failed: ' + (r.stderr || r.status));
}
