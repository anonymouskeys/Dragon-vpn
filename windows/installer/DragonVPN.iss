#define MyAppName "Dragon VPN"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "Anonymous Keys"
#define MyAppExeName "DragonVPN.exe"

[Setup]
AppId={{B4E9C43E-0EA0-4CF0-9C86-D2A60A1B1000}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\Dragon VPN
DefaultGroupName=Dragon VPN
OutputBaseFilename=DragonVPN-Setup-x64
OutputDir=output
Compression=lzma2/max
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
SetupIconFile=..\DragonVPN\Assets\dragon.ico
UninstallDisplayIcon={app}\DragonVPN.exe
PrivilegesRequired=admin
WizardStyle=modern

[Languages]
Name: "en"; MessagesFile: "compiler:Default.isl"
Name: "ru"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "zh_cn"; MessagesFile: "compiler:Default.isl,ChineseSimplified.isl"
Name: "fa"; MessagesFile: "compiler:Default.isl,Persian.isl"

[Files]
Source: "..\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Dragon VPN"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\Dragon VPN"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Registry]
Root: HKCU; Subkey: "Software\AnonymousKeys\DragonVPN"; ValueType: string; ValueName: "Language"; ValueData: "{language}"; Flags: uninsdeletekey

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent
